package org.monolites.monolit.clients;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.vk.api.sdk.objects.callback.MessageNew;
import com.vk.api.sdk.objects.callback.MessageObject;
import com.vk.api.sdk.objects.messages.Message;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.monolites.monolit.configs.ChatClientConfig;
import org.monolites.monolit.models.dtos.chat.ChatRequest;
import org.monolites.monolit.services.ChatSessionService;
import org.monolites.monolit.services.VkChatService;
import org.monolites.monolit.services.VkMessageSenderService;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class CliToChatGptClientTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<String> request = new AtomicReference<>();
    private final CountDownLatch bodyReleased = new CountDownLatch(1);
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private HttpServer server;
    private CliToChatGptClient client;
    private HttpClient http;
    private volatile String response = "{\"requestId\":\"fixture\",\"answer\":\"Ответ\"}";
    private volatile int status = 200;
    private volatile boolean holdBody;

    private static MessageNew question() {
        return new MessageNew().setObject(new MessageObject().setMessage(
                new Message().setFromId(42L).setPeerId(42L).setText("Question")));
    }

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/api/chat", this::respond);
        server.start();
    }

    @AfterEach
    void close() {
        bodyReleased.countDown();
        if (http != null) {
            http.shutdownNow();
        }
        server.stop(0);
        executor.shutdownNow();
    }

    @Test
    void postsOnlyMessageAndStableConversationIdAndPreservesAnswer() throws Exception {
        client = client(Duration.ofSeconds(5));

        var first = client.chat(new ChatRequest("Привет\n\"мир\"", "monolit-fixture"));
        assertThat(mapper.readTree(request.get()).path("message").asText()).isEqualTo("Привет\n\"мир\"");
        var second = client.chat(new ChatRequest("Помнишь вопрос?", "monolit-fixture"));

        assertThat(first.getBody().getAnswer()).isEqualTo("Ответ");
        assertThat(first.getBody().getRequestId()).isEqualTo("fixture");
        assertThat(second.getStatusCode().value()).isEqualTo(200);
        assertThat(mapper.readTree(request.get())).isEqualTo(mapper.readTree(
                "{\"message\":\"Помнишь вопрос?\",\"conversationId\":\"monolit-fixture\"}"));
        assertThat(requests).hasValue(2);
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 409, 502, 503, 504})
    void returnsErrorStatusAndRequestIdWithoutRetryOrPublishingRawBody(int errorStatus) {
        client = client(Duration.ofSeconds(5));
        status = errorStatus;
        response = "not-json: private upstream detail";

        assertThatThrownBy(() -> client.chat(new ChatRequest("Private prompt", "fixture")))
                .isInstanceOfSatisfying(RestClientResponseException.class, error -> {
                    assertThat(error.getStatusCode().value()).isEqualTo(errorStatus);
                    assertThat(error.getResponseHeaders().getFirst("X-Request-Id")).isEqualTo("header-fixture");
                });
        assertThat(requests).hasValue(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "not-json",
            "null",
            "{}",
            "{\"requestId\":\"fixture\"}",
            "{\"requestId\":\"fixture\",\"answer\":\"  \"}",
            "{\"answer\":\"Ответ\"}",
            "{\"requestId\":null,\"answer\":\"Ответ\"}",
            "{\"requestId\":\"\",\"answer\":\"Ответ\"}",
            "{\"requestId\":\"  \",\"answer\":\"Ответ\"}"
    })
    void rejectsMalformedSuccessResponses(String body) {
        client = client(Duration.ofSeconds(5));
        response = body;

        var sender = mock(VkMessageSenderService.class);
        try (var service = new ChatSession(client, sender, Duration.ofSeconds(5))) {
            service.chat.ask(question());
            verify(sender, timeout(5000)).sendMessage("Сервис чата недоступен. Попробуйте позже.");
            verifyNoMoreInteractions(sender);
        }
        assertThat(requests).hasValue(1);
    }

    @Test
    void deadlineIncludesReceivingBodyAfterHeaders() throws Exception {
        client = client(Duration.ofSeconds(30));
        holdBody = true;
        var timedOut = new CountDownLatch(1);
        var sender = mock(VkMessageSenderService.class);
        doAnswer(invocation -> {
            timedOut.countDown();
            return null;
        })
                .when(sender).sendMessage("Ответ чата не получен вовремя. Попробуйте позже.");
        try (var service = new ChatSession(client, sender, Duration.ofSeconds(1))) {
            service.chat.ask(question());
            assertThat(timedOut.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(requests).hasValue(1);
            verify(sender).sendMessage("Ответ чата не получен вовремя. Попробуйте позже.");
            verifyNoMoreInteractions(sender);
        }
        http.shutdown();
        assertThat(http.awaitTermination(Duration.ofSeconds(5))).isTrue();
    }

    @Test
    void doesNotFollowRedirects() {
        client = client(Duration.ofSeconds(5));
        status = 307;

        assertThat(client.chat(new ChatRequest("Question", "fixture")).getStatusCode().value()).isEqualTo(307);
        assertThat(requests).hasValue(1);
    }

    private CliToChatGptClient client(Duration timeout) {
        var config = new ChatClientConfig();
        http = config.chatHttpClient(Duration.ofSeconds(2));
        return config.cliToChatGptClient(RestClient.builder(), http,
                "http://127.0.0.1:" + server.getAddress().getPort(), timeout);
    }

    private void respond(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        assertThat(exchange.getRequestMethod()).isEqualTo("POST");
        assertThat(exchange.getRequestHeaders().getFirst("Content-Type")).startsWith("application/json");
        request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("X-Request-Id", "header-fixture");
        exchange.getResponseHeaders().set("Location", "/api/chat");
        exchange.sendResponseHeaders(status, bytes.length);
        try (exchange) {
            if (holdBody) {
                try {
                    bodyReleased.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            exchange.getResponseBody().write(bytes);
        }
    }

    private static class ChatSession implements AutoCloseable {
        private final VkChatService chat;

        ChatSession(CliToChatGptClient client, VkMessageSenderService sender, Duration timeout) {
            var sessions = mock(ChatSessionService.class);
            when(sessions.currentConversationId()).thenReturn("fixture");
            chat = new VkChatService(client, sender, 42L, sessions, timeout);
        }

        @Override
        public void close() {
            chat.close();
        }
    }
}
