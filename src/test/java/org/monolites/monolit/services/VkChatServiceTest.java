package org.monolites.monolit.services;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.vk.api.sdk.objects.callback.MessageNew;
import com.vk.api.sdk.objects.callback.MessageObject;
import com.vk.api.sdk.objects.messages.Message;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.monolites.monolit.clients.CliToChatGptClient;
import org.monolites.monolit.configs.JacksonConfig;
import org.monolites.monolit.handlers.callbacks.CallbackPayloadDispatcher;
import org.monolites.monolit.handlers.callbacks.ChatCallbackPayloadHandler;
import org.monolites.monolit.models.dtos.chat.ChatRequest;
import org.monolites.monolit.models.dtos.chat.ChatResponse;
import org.monolites.monolit.models.entities.ChatSession;
import org.monolites.monolit.repositories.ChatSessionRepository;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class VkChatServiceTest {

    private final CliToChatGptClient client = mock(CliToChatGptClient.class);
    private final VkMessageSenderService sender = mock(VkMessageSenderService.class);
    private final ManualExecutor worker = new ManualExecutor();
    private final ChatSessionRepository repository = mock(ChatSessionRepository.class);
    private final ChatSessionService sessions;
    private final VkChatService chat;

    VkChatServiceTest() {
        when(repository.findById("main")).thenReturn(Optional.of(new ChatSession("main", "fixture")));
        sessions = new ChatSessionService(repository);
        chat = new VkChatService(client, sender, 42L, sessions, Duration.ofSeconds(5), worker);
    }

    private static RestClientResponseException httpError(int status) {
        var headers = new HttpHeaders();
        headers.set("X-Request-Id", "fixture");
        return new RestClientResponseException("private error", status, "error", headers, null, null);
    }

    private static MessageNew event(long fromId, Long peerId, String text) {
        return new MessageNew().setObject(new MessageObject().setMessage(
                new Message().setFromId(fromId).setPeerId(peerId).setText(text)));
    }

    @Test
    void schedulesWithoutWaitingRejectsSecondQuestionAndReleasesSlotAfterSuccess() {
        when(client.chat(any())).thenReturn(ResponseEntity.ok(new ChatResponse("fixture", "Ответ")));

        chat.ask(event(42L, 42L, "First"));
        verifyNoInteractions(client, sender);
        chat.ask(event(42L, 42L, "Second"));
        verify(sender).sendMessage("Чат занят, повторите позже.");
        worker.runNext();
        chat.ask(event(42L, 42L, "Third"));
        worker.runNext();

        verify(client).chat(refEq(new ChatRequest("First", "fixture")));
        verify(client).chat(refEq(new ChatRequest("Third", "fixture")));
        verify(client, never()).chat(refEq(new ChatRequest("Second", "fixture")));
        verify(sender, times(2)).sendMessage("Ответ");
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 409, 502, 503, 504})
    void reportsHttpErrorsAndReleasesSlotWithoutRetry(int status) {
        when(client.chat(any())).thenThrow(httpError(status))
                .thenReturn(ResponseEntity.ok(new ChatResponse("next", "Recovered")));

        chat.ask(event(42L, 42L, "Failed"));
        worker.runNext();
        chat.ask(event(42L, 42L, "Next"));
        worker.runNext();

        String expected = switch (status) {
            case 409 -> "Чат занят, повторите позже.";
            case 504 -> "Ответ чата не получен вовремя. Попробуйте позже.";
            default -> "Сервис чата недоступен. Попробуйте позже.";
        };
        verify(sender).sendMessage(expected);
        verify(sender).sendMessage("Recovered");
        verify(client).chat(refEq(new ChatRequest("Failed", "fixture")));
        verify(client).chat(refEq(new ChatRequest("Next", "fixture")));
    }

    @Test
    void reportsNetworkAndDeadlineFailuresAndCanAcceptAnotherQuestion() {
        when(client.chat(any())).thenThrow(new ResourceAccessException("private detail"), new ResourceAccessException("timeout", new HttpTimeoutException("deadline")))
                .thenReturn(ResponseEntity.ok(new ChatResponse("fixture", "Recovered")));

        for (String text : List.of("Network", "Deadline", "Next")) {
            chat.ask(event(42L, 42L, text));
            worker.runNext();
        }

        verify(sender).sendMessage("Сервис чата недоступен. Попробуйте позже.");
        verify(sender).sendMessage("Ответ чата не получен вовремя. Попробуйте позже.");
        verify(sender).sendMessage("Recovered");
    }

    @Test
    void splitsLongAnswerWithoutLosingTextOrBreakingEmoji() {
        String answer = "x".repeat(3899) + "🙂" + "y".repeat(4100);
        when(client.chat(any())).thenReturn(ResponseEntity.ok(new ChatResponse("fixture", answer)));

        chat.ask(event(42L, 42L, "Question"));
        worker.runNext();

        ArgumentCaptor<String> parts = ArgumentCaptor.forClass(String.class);
        verify(sender, times(3)).sendMessage(parts.capture());
        assertThat(String.join("", parts.getAllValues())).isEqualTo(answer);
        assertThat(parts.getAllValues()).allSatisfy(part -> {
            assertThat(part.length()).isLessThanOrEqualTo(3900);
            assertThat(Character.isHighSurrogate(part.charAt(part.length() - 1))).isFalse();
            assertThat(Character.isLowSurrogate(part.charAt(0))).isFalse();
        });
    }

    @Test
    void ignoresForeignUsersGroupChatsAndMissingPeer() {
        chat.ask(event(99L, 99L, "Foreign"));
        chat.ask(event(42L, 2_000_000_001L, "Group"));
        chat.ask(event(42L, null, "Missing peer"));
        chat.startNewConversation(event(99L, 99L, "New"));
        chat.startNewConversation(event(42L, 2_000_000_001L, "New"));
        chat.startNewConversation(event(42L, null, "New"));

        assertThat(worker.pending).isNull();
        verifyNoInteractions(client, sender);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void newConversationSharesSlotAndQuestionsKeepTheIdSelectedAtAdmission() {
        when(client.chat(any())).thenReturn(ResponseEntity.ok(new ChatResponse("fixture", "Ответ")));

        chat.ask(event(42L, 42L, "Before"));
        chat.startNewConversation(event(42L, 42L, "New while busy"));
        assertThat(sessions.currentConversationId()).isEqualTo("fixture");
        verify(repository, never()).saveAndFlush(any());
        worker.runNext();

        chat.startNewConversation(event(42L, 42L, "New"));
        chat.ask(event(42L, 42L, "While switching"));
        chat.startNewConversation(event(42L, 42L, "Second switch"));
        verify(repository, never()).saveAndFlush(any());
        worker.runNext();
        String nextId = sessions.currentConversationId();
        assertThat(nextId).isNotEqualTo("fixture");

        chat.ask(event(42L, 42L, "After"));
        worker.runNext();
        verify(client).chat(refEq(new ChatRequest("Before", "fixture")));
        verify(client).chat(refEq(new ChatRequest("After", nextId)));
        verify(client, times(2)).chat(any());
        verify(repository).saveAndFlush(any());
        verify(sender, times(3)).sendMessage("Чат занят, повторите позже.");
        verify(sender).sendMessage("Новый чат готов. Напишите сообщение.");
    }

    @Test
    void failedSaveKeepsConversationAndReleasesSlotWithoutSuccessMessage() {
        when(repository.saveAndFlush(any())).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("PRIVATE_DB_DETAIL"));
        when(client.chat(any())).thenReturn(ResponseEntity.ok(new ChatResponse("fixture", "Recovered")));

        chat.startNewConversation(event(42L, 42L, "New"));
        worker.runNext();
        chat.ask(event(42L, 42L, "After failure"));
        worker.runNext();

        verify(sender).sendMessage("Не удалось начать новый чат. Попробуйте позже.");
        verify(sender, never()).sendMessage("Новый чат готов. Напишите сообщение.");
        verify(client).chat(refEq(new ChatRequest("After failure", "fixture")));
    }

    @Test
    void newChatPayloadThenExitAndReenterUseTheNewConversation() throws Exception {
        var modes = new DialogueModeService();
        var callback = new ChatCallbackPayloadHandler(modes, chat, sender);
        var mapper = new JacksonConfig().objectMapper();
        var dispatcher = new CallbackPayloadDispatcher(mapper, List.of(callback));
        var event = event(42L, 42L, "Button");
        String newPayload = mapper.writeValueAsString(ChatCallbackPayloadHandler.NEW_CHAT_PAYLOAD);
        dispatcher.dispatch(newPayload, event);
        assertThat(worker.pending).isNull();

        dispatcher.dispatch(mapper.writeValueAsString(ChatCallbackPayloadHandler.ENTER_CHAT_PAYLOAD), event);
        dispatcher.dispatch(newPayload, event);
        worker.runNext();
        String selected = sessions.currentConversationId();
        assertThat(selected).isNotEqualTo("fixture");
        dispatcher.dispatch(mapper.writeValueAsString(ChatCallbackPayloadHandler.EXIT_CHAT_PAYLOAD), event);
        dispatcher.dispatch(mapper.writeValueAsString(ChatCallbackPayloadHandler.ENTER_CHAT_PAYLOAD), event);
        when(client.chat(any())).thenReturn(ResponseEntity.ok(new ChatResponse("new", "Answer")));
        chat.ask(event(42L, 42L, "First question"));
        worker.runNext();
        chat.ask(event(42L, 42L, "Followup"));
        worker.runNext();

        verify(client).chat(refEq(new ChatRequest("First question", selected)));
        verify(client).chat(refEq(new ChatRequest("Followup", selected)));
        verify(repository).saveAndFlush(any());
        assertThat(modes.currentMode()).isEqualTo(DialogueModeService.Mode.CHATGPT);
    }

    @Test
    void logsCorrelationAndCategoryWithoutPromptAnswerOrRawFailureDetails() {
        Logger logger = (Logger) LoggerFactory.getLogger(VkChatService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        when(client.chat(any())).thenReturn(ResponseEntity.ok(new ChatResponse("request-fixture", "PRIVATE_ANSWER")))
                .thenThrow(new ResourceAccessException("RAW_PRIVATE_BODY"));
        try {
            chat.ask(event(42L, 42L, "PRIVATE_PROMPT"));
            worker.runNext();
            chat.ask(event(42L, 42L, "PRIVATE_PROMPT"));
            worker.runNext();

            assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
                    .anySatisfy(message -> assertThat(message).contains("request-fixture"))
                    .allSatisfy(message -> assertThat(message)
                            .doesNotContain("PRIVATE_PROMPT", "PRIVATE_ANSWER", "RAW_PRIVATE_BODY"));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void shutdownRejectsSubmissionsWithControlledMessage() {
        chat.close();

        chat.ask(event(42L, 42L, "Question"));
        chat.ask(event(42L, 42L, "Another"));
        chat.startNewConversation(event(42L, 42L, "New"));

        verify(sender, times(3)).sendMessage("Сервис чата недоступен. Попробуйте позже.");
        verifyNoInteractions(client);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void shutdownInterruptsActiveWork() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch blocked = new CountDownLatch(1);
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        when(client.chat(any())).thenAnswer(invocation -> {
            started.countDown();
            blocked.await();
            return ResponseEntity.ok(new ChatResponse("fixture", "Unreachable"));
        });
        VkChatService service = new VkChatService(client, sender, 42L, sessions, Duration.ofSeconds(5), executor);
        try {
            service.ask(event(42L, 42L, "Question"));
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

            service.close();

            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            verifyNoInteractions(sender);
        } finally {
            blocked.countDown();
            service.close();
        }
    }

    private static class ManualExecutor extends AbstractExecutorService {
        private Runnable pending;
        private boolean shutdown;
        private boolean running;

        void runNext() {
            Runnable task = pending;
            pending = null;
            running = true;
            try {
                task.run();
            } finally {
                running = false;
            }
        }

        @Override
        public void execute(Runnable task) {
            if (shutdown) {
                throw new RejectedExecutionException();
            }
            if (running) {
                task.run();
            } else {
                assertThat(pending).isNull();
                pending = task;
            }
        }

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            return pending == null ? List.of() : List.of(pending);
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return isShutdown();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return isTerminated();
        }
    }
}
