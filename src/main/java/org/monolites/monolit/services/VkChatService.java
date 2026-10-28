package org.monolites.monolit.services;

import com.vk.api.sdk.objects.callback.MessageNew;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.monolites.monolit.clients.CliToChatGptClient;
import org.monolites.monolit.models.dtos.chat.ChatRequest;
import org.monolites.monolit.models.dtos.chat.ChatResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
@ConditionalOnProperty(name = "monolit.chat.enabled", havingValue = "true")
public class VkChatService {

    private static final int MAX_MESSAGE_LENGTH = 3900;
    private static final String BUSY = "Чат занят, повторите позже.";
    private static final String UNAVAILABLE = "Сервис чата недоступен. Попробуйте позже.";
    private static final String TIMEOUT_MESSAGE = "Ответ чата не получен вовремя. Попробуйте позже.";

    private final CliToChatGptClient client;
    private final VkMessageSenderService sender;
    private final long ownerId;
    private final ExecutorService worker;
    private final ChatSessionService sessions;
    private final Duration timeout;
    private final AtomicBoolean active = new AtomicBoolean();

    @Autowired
    public VkChatService(CliToChatGptClient client, VkMessageSenderService sender,
                         @Value("${VK_MY_ID}") long ownerId,
                         ChatSessionService sessions,
                         @Value("${monolit.chat.request-timeout:PT11M}") Duration timeout) {
        this(client, sender, ownerId, sessions, timeout, Executors.newVirtualThreadPerTaskExecutor());
    }

    VkChatService(CliToChatGptClient client, VkMessageSenderService sender,
                  long ownerId, ChatSessionService sessions, Duration timeout, ExecutorService worker) {
        Assert.isTrue(timeout.toMillis() > 0, "Chat timeout must be positive");
        this.sessions = sessions;
        this.timeout = timeout;
        this.client = client;
        this.sender = sender;
        this.ownerId = ownerId;
        this.worker = worker;
    }

    public boolean accepts(MessageNew event) {
        var message = event.getObject().getMessage();
        return Objects.equals(message.getFromId(), ownerId) && Objects.equals(message.getPeerId(), ownerId);
    }

    public void ask(MessageNew event) {
        String text = event.getObject().getMessage().getText();
        if (!accepts(event)) {
            return;
        }
        if (!tryStart()) {
            return;
        }
        String conversationId = sessions.currentConversationId();
        execute(() -> answer(text, conversationId));
    }

    public void startNewConversation(MessageNew event) {
        if (!accepts(event) || !tryStart()) {
            return;
        }
        execute(() -> {
            try {
                sessions.startNewConversation();
                log.info("Chat conversation switched");
                sender.sendMessage("Новый чат готов. Напишите сообщение.");
            } catch (RuntimeException e) {
                log.warn("Chat switch failed category={}", e.getClass().getSimpleName());
                sender.sendMessage("Не удалось начать новый чат. Попробуйте позже.");
            }
        });
    }

    private boolean tryStart() {
        if (active.compareAndSet(false, true)) {
            return true;
        }
        sender.sendMessage(BUSY);
        return false;
    }

    private void execute(Runnable operation) {
        try {
            worker.execute(() -> {
                try {
                    operation.run();
                } finally {
                    active.set(false);
                }
            });
        } catch (RejectedExecutionException e) {
            active.set(false);
            log.warn("Chat request failed category=REJECTED");
            sender.sendMessage(UNAVAILABLE);
        }
    }

    private void answer(String text, String conversationId) {
        long started = System.nanoTime();
        Future<ResponseEntity<ChatResponse>> pending = null;
        try {
            pending = worker.submit(() -> client.chat(new ChatRequest(text, conversationId)));
            // JDK readTimeout covers headers; this deadline also covers body decoding.
            var response = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            var reply = response.getBody();
            Assert.notNull(reply, "Chat response requires a body");
            Assert.hasText(reply.getRequestId(), "Chat response requires requestId");
            Assert.hasText(reply.getAnswer(), "Chat response requires answer");
            log.info("Chat response status={} requestId={} durationMs={}", response.getStatusCode().value(),
                    reply.getRequestId(), (System.nanoTime() - started) / 1_000_000);
            if (response.getStatusCode().value() == 200) {
                sendParts(reply.getAnswer());
            } else {
                sender.sendMessage(UNAVAILABLE);
            }
        } catch (TimeoutException | ExecutionException | RuntimeException e) {
            reportFailure(e, started);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Chat request failed category=INTERRUPTED durationMs={}", (System.nanoTime() - started) / 1_000_000);
        } finally {
            if (pending != null) {
                pending.cancel(true);
            }
        }
    }

    private void reportFailure(Throwable failure, long started) {
        Throwable cause = NestedExceptionUtils.getMostSpecificCause(failure);
        String message = UNAVAILABLE;
        if (cause instanceof RestClientResponseException response) {
            int status = response.getStatusCode().value();
            String requestId = response.getResponseHeaders() == null ? null
                    : response.getResponseHeaders().getFirst("X-Request-Id");
            log.warn("Chat response status={} requestId={} durationMs={}", status, requestId,
                    (System.nanoTime() - started) / 1_000_000);
            message = switch (status) {
                case 409 -> BUSY;
                case 504 -> TIMEOUT_MESSAGE;
                default -> UNAVAILABLE;
            };
        } else {
            log.warn("Chat request failed category={} durationMs={}", cause.getClass().getSimpleName(),
                    (System.nanoTime() - started) / 1_000_000);
            if (cause instanceof TimeoutException || cause instanceof HttpTimeoutException) {
                message = TIMEOUT_MESSAGE;
            }
        }
        sender.sendMessage(message);
    }

    private void sendParts(String answer) {
        int start = 0;
        while (start < answer.length()) {
            int end = Math.min(start + MAX_MESSAGE_LENGTH, answer.length());
            if (end < answer.length() && Character.isHighSurrogate(answer.charAt(end - 1))
                    && Character.isLowSurrogate(answer.charAt(end))) {
                end--;
            }
            sender.sendMessage(answer.substring(start, end));
            start = end;
        }
    }

    @PreDestroy
    public void close() {
        worker.shutdownNow();
    }
}
