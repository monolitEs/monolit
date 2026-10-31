package org.monolites.monolit.clients;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.vk.api.sdk.client.VkApiClient;
import com.vk.api.sdk.client.actors.GroupActor;
import com.vk.api.sdk.objects.callback.MessageNew;
import com.vk.api.sdk.objects.callback.MessageObject;
import com.vk.api.sdk.objects.messages.Keyboard;
import com.vk.api.sdk.objects.messages.KeyboardButtonActionText;
import com.vk.api.sdk.objects.messages.Message;
import com.vk.api.sdk.queries.messages.MessagesSendQueryWithDeprecated;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.monolites.monolit.configs.ChatClientConfig;
import org.monolites.monolit.configs.JacksonConfig;
import org.monolites.monolit.handlers.GroupLongPoolApiHandler;
import org.monolites.monolit.handlers.callbacks.CallbackPayloadDispatcher;
import org.monolites.monolit.handlers.callbacks.ChatCallbackPayloadHandler;
import org.monolites.monolit.handlers.text.ChatGptTextMessageHandler;
import org.monolites.monolit.handlers.text.TextMessageDispatcher;
import org.monolites.monolit.models.dtos.chat.ChatSettingsRequest;
import org.monolites.monolit.services.ChatSessionService;
import org.monolites.monolit.services.DialogueModeService;
import org.monolites.monolit.services.VkChatService;
import org.monolites.monolit.services.VkMessageSenderService;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ChatSettingsClientTest {

    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final CountDownLatch releaseBody = new CountDownLatch(1);
    private final java.util.concurrent.ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private HttpServer server;
    private HttpClient http;
    private CliToChatGptClient client;
    private VkChatService chat;
    private volatile int status = 200;
    private volatile int putStatus = 200;
    private volatile boolean holdPutBody;
    private volatile String getBody = "{\"requestId\":\"get\",\"conversationId\":\"fixture\",\"model\":\"m\",\"reasoningEffort\":\"low\"}";
    private volatile String putBody = "{\"requestId\":\"put\",\"conversationId\":\"fixture\",\"model\":\"m\",\"reasoningEffort\":\"high\"}";

    private static MessageNew event() {
        return new MessageNew().setObject(new MessageObject().setMessage(
                new Message().setFromId(42L).setPeerId(42L).setText("Thinking")));
    }

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/api/chat/settings", this::respond);
        server.start();
        var config = new ChatClientConfig();
        http = config.chatHttpClient(Duration.ofSeconds(2));
        client = config.cliToChatGptClient(RestClient.builder(), http,
                "http://127.0.0.1:" + server.getAddress().getPort(), Duration.ofSeconds(30));
    }

    @AfterEach
    void close() {
        releaseBody.countDown();
        if (chat != null) {
            chat.close();
        }
        http.shutdownNow();
        server.stop(0);
        executor.shutdownNow();
    }

    @Test
    void getAndPutUseQueryParametersAndOnlyModelAndEffortInJson() throws Exception {
        String id = "a +/?:&Рус";
        assertThat(client.getSettings(id).getBody().model()).isEqualTo("m");
        assertThat(client.updateSettings(id, new ChatSettingsRequest("m", "high")).getBody().reasoningEffort()).isEqualTo("high");

        assertThat(requests).extracting(Request::method).containsExactly("GET", "PUT");
        assertThat(requests).allSatisfy(request -> {
            assertThat(request.query()).startsWith("conversationId=").doesNotContain("&");
            assertThat(URLDecoder.decode(request.query().substring("conversationId=".length()), StandardCharsets.UTF_8)).isEqualTo(id);
        });
        assertThat(requests.getFirst().body()).isEmpty();
        assertThat(requests.getLast().contentType()).startsWith("application/json");
        assertThat(new ObjectMapper().readTree(requests.getLast().body())).isEqualTo(
                new ObjectMapper().readTree("{\"model\":\"m\",\"reasoningEffort\":\"high\"}"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "not-json", "{}", "{\"requestId\":\"r\"}",
            "{\"requestId\":\"\",\"conversationId\":\"fixture\"}",
            "{\"requestId\":\"r\",\"conversationId\":\"other\",\"model\":\"m\"}"})
    void malformedGetIsNotDisplayedAsSuccess(String body) {
        getBody = body;
        var sender = mock(VkMessageSenderService.class);
        service(sender, Duration.ofSeconds(5)).readThinking(event(), settings -> sender.sendMessage("UNEXPECTED_SUCCESS"));

        verify(sender, timeout(5000)).sendMessage("Сервис чата недоступен. Попробуйте позже.");
        verify(sender, never()).sendMessage("UNEXPECTED_SUCCESS");
        assertThat(requests).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"code\":\"CONVERSATION_NOT_FOUND\"}", "{\"code\":\"NOT_FOUND\"}", "not-json"})
    void onlyConversationNotFoundMeansFirstMessageIsRequired(String body) {
        status = 404;
        getBody = body;
        var sender = mock(VkMessageSenderService.class);
        service(sender, Duration.ofSeconds(5)).readThinking(event(), settings -> sender.sendMessage("UNEXPECTED_SUCCESS"));

        String expected = body.contains("CONVERSATION_NOT_FOUND")
                ? "Сначала отправьте сообщение в этом чате, затем откройте «Мышление»."
                : "Сервис чата недоступен. Попробуйте позже.";
        verify(sender, timeout(5000)).sendMessage(expected);
        verify(sender, never()).sendMessage("UNEXPECTED_SUCCESS");
        assertThat(requests).hasSize(1);
    }

    @Test
    void deadlineCoversPutBodyAfterSuccessfulGetAndNeverRetriesTheMutation() throws Exception {
        holdPutBody = true;
        var sender = mock(VkMessageSenderService.class);
        var timedOut = new CountDownLatch(1);
        doAnswer(invocation -> {
            timedOut.countDown();
            return null;
        })
                .when(sender).sendMessage("Ответ чата не получен вовремя. Попробуйте позже.");
        service(sender, Duration.ofSeconds(1)).changeThinking(event(), "fixture", "high", settings -> sender.sendMessage("UNEXPECTED_SUCCESS"));

        assertThat(timedOut.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(requests).extracting(Request::method).containsExactly("GET", "PUT");
        verify(sender, never()).sendMessage("UNEXPECTED_SUCCESS");
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 404, 409, 502, 503, 504})
    void rejectedPutIsNotConfirmedOrRetried(int rejectedStatus) {
        putStatus = rejectedStatus;
        putBody = "{\"code\":\"CONVERSATION_NOT_FOUND\",\"detail\":\"PRIVATE_UPSTREAM_DETAIL\"}";
        var sender = mock(VkMessageSenderService.class);
        service(sender, Duration.ofSeconds(5)).changeThinking(event(), "fixture", "high", settings -> sender.sendMessage("UNEXPECTED_SUCCESS"));

        String expected = switch (rejectedStatus) {
            case 400 -> "Этот уровень мышления недоступен для текущей модели.";
            case 404 -> "Сначала отправьте сообщение в этом чате, затем откройте «Мышление».";
            case 409 -> "Чат занят, повторите позже.";
            case 504 -> "Ответ чата не получен вовремя. Попробуйте позже.";
            default -> "Сервис чата недоступен. Попробуйте позже.";
        };
        verify(sender, timeout(5000)).sendMessage(expected);
        verify(sender, never()).sendMessage("UNEXPECTED_SUCCESS");
        assertThat(requests).extracting(Request::method).containsExactly("GET", "PUT");
    }

    @Test
    void keyboardPayloadThroughLongPollAndRealHttpProducesEffortButtonsForCurrentChat() {
        var vk = mock(VkApiClient.class, RETURNS_DEEP_STUBS);
        var actor = mock(GroupActor.class);
        var query = mock(MessagesSendQueryWithDeprecated.class, RETURNS_SELF);
        when(vk.messages().sendDeprecated(actor)).thenReturn(query);
        var mapper = new JacksonConfig().objectMapper();
        var sender = new VkMessageSenderService(vk, actor, mapper, "42");
        var service = service(sender, Duration.ofSeconds(5));
        var modes = new DialogueModeService();
        var callback = new ChatCallbackPayloadHandler(modes, service, sender);
        var callbacks = new CallbackPayloadDispatcher(mapper, List.of(callback));
        var texts = new TextMessageDispatcher(modes, List.of(new ChatGptTextMessageHandler(service)));
        var handler = new GroupLongPoolApiHandler(vk, actor, callbacks, texts, "42");
        callback.handle("enter", event());
        ArgumentCaptor<Keyboard> keyboards = ArgumentCaptor.forClass(Keyboard.class);
        verify(query).keyboard(keyboards.capture());
        var thinking = keyboards.getValue().getButtons().stream().flatMap(List::stream)
                .map(button -> (KeyboardButtonActionText) button.getAction())
                .filter(button -> "Мышление".equals(button.getLabel())).findFirst().orElseThrow();
        var click = event();
        click.getObject().getMessage().setPayload(thinking.getPayload());
        handler.messageNew(1, click);

        verify(query, timeout(5000).times(2)).keyboard(keyboards.capture());
        var keyboard = keyboards.getValue();
        assertThat(keyboard.getButtons()).extracting(List::size).containsExactly(2, 2, 2);
        assertThat(keyboard.getInline()).isFalse();
        var buttons = keyboard.getButtons().stream().flatMap(List::stream)
                .map(button -> (KeyboardButtonActionText) button.getAction()).toList();
        assertThat(buttons).extracting(KeyboardButtonActionText::getLabel)
                .containsExactly("low", "medium", "high", "xhigh", "Назад", "Выход");
        assertThat(buttons.get(2).getPayload()).contains("effort:fixture:high");
        assertThat(requests).extracting(Request::method).containsExactly("GET");
        verify(query).message("Модель: m\nМышление: low\nВыберите уровень.");
    }

    private VkChatService service(VkMessageSenderService sender, Duration deadline) {
        var sessions = mock(ChatSessionService.class);
        when(sessions.currentConversationId()).thenReturn("fixture");
        chat = new VkChatService(client, sender, 42L, sessions, deadline);
        return chat;
    }

    private void respond(HttpExchange exchange) throws IOException {
        requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI().getRawQuery(),
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), exchange.getRequestHeaders().getFirst("Content-Type")));
        byte[] bytes = (exchange.getRequestMethod().equals("PUT") ? putBody : getBody).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("X-Request-Id", "settings-fixture");
        exchange.sendResponseHeaders(exchange.getRequestMethod().equals("PUT") ? putStatus : status, bytes.length);
        try (exchange) {
            if (holdPutBody && exchange.getRequestMethod().equals("PUT")) {
                try {
                    releaseBody.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            exchange.getResponseBody().write(bytes);
        }
    }

    private record Request(String method, String query, String body, String contentType) {
    }
}
