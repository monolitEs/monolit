package org.monolites.monolit.handlers;

import com.vk.api.sdk.client.VkApiClient;
import com.vk.api.sdk.client.actors.GroupActor;
import com.vk.api.sdk.objects.callback.MessageNew;
import com.vk.api.sdk.objects.callback.MessageObject;
import com.vk.api.sdk.objects.messages.Keyboard;
import com.vk.api.sdk.objects.messages.KeyboardButtonActionText;
import com.vk.api.sdk.objects.messages.Message;
import com.vk.api.sdk.queries.messages.MessagesSendQueryWithDeprecated;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.monolites.monolit.clients.CliToChatGptClient;
import org.monolites.monolit.configs.JacksonConfig;
import org.monolites.monolit.handlers.callbacks.CallbackPayloadDispatcher;
import org.monolites.monolit.handlers.callbacks.ChatCallbackPayloadHandler;
import org.monolites.monolit.handlers.callbacks.NewsCallbackPayloadHandler;
import org.monolites.monolit.handlers.text.ChatGptTextMessageHandler;
import org.monolites.monolit.handlers.text.TextMessageDispatcher;
import org.monolites.monolit.init.VkKeyboardInitializer;
import org.monolites.monolit.models.dtos.chat.ChatRequest;
import org.monolites.monolit.models.dtos.chat.ChatResponse;
import org.monolites.monolit.models.entities.ChatSession;
import org.monolites.monolit.repositories.ChatSessionRepository;
import org.monolites.monolit.services.*;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.Mockito.*;
import static org.monolites.monolit.services.DialogueModeService.Mode.CHATGPT;
import static org.monolites.monolit.services.DialogueModeService.Mode.DEFAULT;

class ChatButtonFlowTest {

    private static KeyboardButtonActionText action(MessagesSendQueryWithDeprecated query, String label) {
        ArgumentCaptor<Keyboard> keyboards = ArgumentCaptor.forClass(Keyboard.class);
        verify(query, atLeastOnce()).keyboard(keyboards.capture());
        Keyboard keyboard = keyboards.getValue();
        assertThat(keyboard.getInline()).isFalse();
        assertThat(keyboard.getOneTime()).isFalse();
        return keyboard.getButtons().stream().flatMap(List::stream)
                .map(button -> (KeyboardButtonActionText) button.getAction())
                .filter(action -> label.equals(action.getLabel())).findFirst().orElseThrow();
    }

    private static MessageNew event(long fromId, long peerId, String text, String payload) {
        return new MessageNew().setObject(new MessageObject().setMessage(
                new Message().setFromId(fromId).setPeerId(peerId).setText(text).setPayload(payload)));
    }

    @Test
    void startupEnterQuestionBusyNewsAndExitKeepModeIndependentOfPendingAnswer() throws Exception {
        var vk = mock(VkApiClient.class, RETURNS_DEEP_STUBS);
        var query = mock(MessagesSendQueryWithDeprecated.class, RETURNS_SELF);
        var actor = mock(GroupActor.class);
        when(vk.messages().sendDeprecated(actor)).thenReturn(query);
        var mapper = new JacksonConfig().objectMapper();
        var sender = new VkMessageSenderService(vk, actor, mapper, "42");
        var client = mock(CliToChatGptClient.class);
        var modes = new DialogueModeService();
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var answerSent = new CountDownLatch(1);
        when(client.chat(refEq(new ChatRequest("Question", "fixture")))).thenAnswer(invocation -> {
            started.countDown();
            release.await();
            return ResponseEntity.ok(new ChatResponse("fixture", "Answer"));
        });
        when(query.message("Answer")).thenAnswer(invocation -> {
            answerSent.countDown();
            return query;
        });
        var repository = mock(ChatSessionRepository.class);
        when(repository.findById("main")).thenReturn(Optional.of(new ChatSession("main", "fixture")));
        var sessions = new ChatSessionService(repository);
        var chat = new VkChatService(client, sender, 42L, sessions, Duration.ofSeconds(5));
        try {
            var callback = new ChatCallbackPayloadHandler(modes, chat, sender);
            var news = mock(NewsService.class);
            var callbacks = new CallbackPayloadDispatcher(mapper,
                    List.of(callback, new NewsCallbackPayloadHandler(news)));
            var texts = new TextMessageDispatcher(modes, List.of(new ChatGptTextMessageHandler(chat)));
            var handler = new GroupLongPoolApiHandler(vk, actor, callbacks, texts, "42");

            new VkKeyboardInitializer(sender, Optional.of(callback)).run();
            String enter = action(query, "ChatGPT").getPayload();
            String newsPayload = action(query, "Получить новости").getPayload();
            handler.messageNew(1, event(42L, 42L, "ChatGPT", null));
            handler.messageNew(1, event(99L, 99L, "ChatGPT", enter));
            handler.messageNew(1, event(42L, 2_000_000_001L, "ChatGPT", enter));
            assertThat(modes.currentMode()).isEqualTo(DEFAULT);
            verifyNoInteractions(client);

            handler.messageNew(1, event(42L, 42L, "ChatGPT", enter));
            assertThat(modes.currentMode()).isEqualTo(CHATGPT);
            String exit = action(query, "Выход").getPayload();
            String newChat = action(query, "Новый чат").getPayload();
            ArgumentCaptor<Keyboard> keyboards = ArgumentCaptor.forClass(Keyboard.class);
            verify(query, atLeastOnce()).keyboard(keyboards.capture());
            assertThat(keyboards.getValue().getButtons().stream().flatMap(List::stream)
                    .map(button -> ((KeyboardButtonActionText) button.getAction()).getLabel()))
                    .containsExactly("Новый чат", "Мышление", "Выход");
            handler.messageNew(1, event(42L, 42L, "Unknown", "{\"type\":\"chat\",\"version\":1,\"data\":\"unknown\"}"));
            handler.messageNew(1, event(42L, 42L, "Unknown", "{\"type\":\"unknown\",\"version\":1,\"data\":null}"));
            handler.messageNew(1, event(42L, 42L, "Malformed", "not-json"));
            handler.messageNew(1, event(42L, 2_000_000_001L, "Group question", null));
            verifyNoInteractions(client);

            assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
                    handler.messageNew(1, event(42L, 42L, "Question", null)));
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            handler.messageNew(1, event(42L, 42L, "Новый чат", newChat));
            verify(repository, never()).saveAndFlush(any());
            handler.messageNew(1, event(42L, 42L, "Second question", null));
            verify(query, times(2)).message("Чат занят, повторите позже.");
            handler.messageNew(1, event(42L, 42L, "Получить новости", newsPayload));
            verify(news).publishLatestNews();

            handler.messageNew(1, event(42L, 42L, "Выход", exit));
            assertThat(modes.currentMode()).isEqualTo(DEFAULT);
            handler.messageNew(1, event(42L, 42L, "Ignored after exit", null));
            handler.messageNew(1, event(42L, 42L, "Новый чат", newChat));
            verify(repository, never()).saveAndFlush(any());
            release.countDown();
            assertThat(answerSent.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(modes.currentMode()).isEqualTo(DEFAULT);
            verify(query, times(3)).keyboard(any());
            verify(client).chat(refEq(new ChatRequest("Question", "fixture")));
            verifyNoMoreInteractions(client);

            handler.messageNew(1, event(42L, 42L, "ChatGPT", enter));
            assertThat(modes.currentMode()).isEqualTo(CHATGPT);
            handler.messageNew(1, event(99L, 99L, "Новый чат", newChat));
            handler.messageNew(1, event(42L, 2_000_000_001L, "Новый чат", newChat));
            verify(repository, never()).saveAndFlush(any());
            assertThat(new DialogueModeService().currentMode()).isEqualTo(DEFAULT);
        } finally {
            release.countDown();
            chat.close();
        }
    }
}
