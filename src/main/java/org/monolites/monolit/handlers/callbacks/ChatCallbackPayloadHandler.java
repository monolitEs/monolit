package org.monolites.monolit.handlers.callbacks;

import com.vk.api.sdk.objects.callback.MessageNew;
import lombok.RequiredArgsConstructor;
import org.monolites.monolit.models.dtos.callback.CallbackPayloadEnvelope;
import org.monolites.monolit.services.DialogueModeService;
import org.monolites.monolit.services.VkChatService;
import org.monolites.monolit.services.VkMessageSenderService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.vk.api.sdk.objects.messages.KeyboardButtonActionTextType.TEXT;
import static org.monolites.monolit.handlers.callbacks.NewsCallbackPayloadHandler.LATEST_NEWS_PAYLOAD;
import static org.monolites.monolit.services.DialogueModeService.Mode.CHATGPT;
import static org.monolites.monolit.services.DialogueModeService.Mode.DEFAULT;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "monolit.chat.enabled", havingValue = "true")
public class ChatCallbackPayloadHandler implements CallbackPayloadHandler<String> {

    public static final CallbackPayloadEnvelope ENTER_CHAT_PAYLOAD = new CallbackPayloadEnvelope("chat", 1, "enter");
    public static final CallbackPayloadEnvelope EXIT_CHAT_PAYLOAD = new CallbackPayloadEnvelope("chat", 1, "exit");

    private final DialogueModeService modes;
    private final VkChatService chat;
    private final VkMessageSenderService sender;

    @Override
    public String type() {
        return "chat";
    }

    @Override
    public int version() {
        return 1;
    }

    @Override
    public Class<String> payloadClass() {
        return String.class;
    }

    @Override
    public void handle(String payload, MessageNew event) {
        if (payload == null || !chat.accepts(event)) {
            return;
        }
        switch (payload) {
            case "enter" -> {
                modes.setMode(CHATGPT);
                showKeyboard("Режим ChatGPT включён. Напишите сообщение.", true);
            }
            case "exit" -> {
                modes.setMode(DEFAULT);
                showMenu("Вы вышли из режима ChatGPT.");
            }
            default -> {
                // Unknown commands are ignored to preserve the current dialogue mode.
            }
        }
    }

    public void showMenu(String message) {
        showKeyboard(message, false);
    }

    private void showKeyboard(String message, boolean chatting) {
        sender.sendMessage(message, List.of(TEXT, TEXT),
                List.of("Получить новости", chatting ? "Выход" : "ChatGPT"),
                List.of(LATEST_NEWS_PAYLOAD, chatting ? EXIT_CHAT_PAYLOAD : ENTER_CHAT_PAYLOAD), false);
    }
}
