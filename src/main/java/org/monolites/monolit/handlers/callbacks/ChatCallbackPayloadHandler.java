package org.monolites.monolit.handlers.callbacks;

import com.vk.api.sdk.objects.callback.MessageNew;
import lombok.RequiredArgsConstructor;
import org.monolites.monolit.models.dtos.callback.CallbackPayloadEnvelope;
import org.monolites.monolit.models.dtos.chat.ChatSettingsResponse;
import org.monolites.monolit.services.DialogueModeService;
import org.monolites.monolit.services.VkChatService;
import org.monolites.monolit.services.VkMessageSenderService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
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
    public static final CallbackPayloadEnvelope NEW_CHAT_PAYLOAD = new CallbackPayloadEnvelope("chat", 1, "new");
    public static final CallbackPayloadEnvelope THINKING_PAYLOAD = new CallbackPayloadEnvelope("chat", 1, "thinking");
    private static final CallbackPayloadEnvelope BACK_PAYLOAD = new CallbackPayloadEnvelope("chat", 1, "back");
    private static final List<String> EFFORTS = List.of("low", "medium", "high", "xhigh");

    private final DialogueModeService modes;
    private final VkChatService chat;
    private final VkMessageSenderService sender;
    private long keyboardVersion;

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
    public synchronized void handle(String payload, MessageNew event) {
        if (payload == null || !chat.accepts(event)) {
            return;
        }
        switch (payload) {
            case "enter" -> {
                keyboardVersion++;
                modes.setMode(CHATGPT);
                showKeyboard("Режим ChatGPT включён. Напишите сообщение.", true);
            }
            case "exit" -> {
                keyboardVersion++;
                modes.setMode(DEFAULT);
                showMenu("Вы вышли из режима ChatGPT.");
            }
            case "new" -> {
                if (modes.currentMode() == CHATGPT) {
                    chat.startNewConversation(event);
                }
            }
            case "thinking" -> {
                if (modes.currentMode() == CHATGPT) {
                    long version = keyboardVersion;
                    chat.readThinking(event, settings -> showThinking(settings, version));
                }
            }
            case "back" -> {
                if (modes.currentMode() == CHATGPT) {
                    keyboardVersion++;
                    showKeyboard("Напишите сообщение.", true);
                }
            }
            default -> {
                String[] parts = payload.split(":", 3);
                if (modes.currentMode() == CHATGPT && parts.length == 3
                        && "effort".equals(parts[0]) && EFFORTS.contains(parts[2])) {
                    long version = keyboardVersion;
                    chat.changeThinking(event, parts[1], parts[2], settings -> showThinking(settings, version));
                }
            }
        }
    }

    public void showMenu(String message) {
        showKeyboard(message, false);
    }

    private void showKeyboard(String message, boolean chatting) {
        if (chatting) {
            sender.sendMessage(message, List.of(TEXT, TEXT, TEXT),
                    List.of("Новый чат", "Мышление", "Выход"),
                    List.of(NEW_CHAT_PAYLOAD, THINKING_PAYLOAD, EXIT_CHAT_PAYLOAD), false);
            return;
        }
        sender.sendMessage(message, List.of(TEXT, TEXT),
                List.of("Получить новости", "ChatGPT"),
                List.of(LATEST_NEWS_PAYLOAD, ENTER_CHAT_PAYLOAD), false);
    }

    private synchronized void showThinking(ChatSettingsResponse settings, long version) {
        if (modes.currentMode() != CHATGPT || keyboardVersion != version) {
            return;
        }
        List<Object> payloads = new ArrayList<>();
        for (String effort : EFFORTS) {
            payloads.add(new CallbackPayloadEnvelope("chat", 1, "effort:" + settings.conversationId() + ":" + effort));
        }
        payloads.add(BACK_PAYLOAD);
        payloads.add(EXIT_CHAT_PAYLOAD);
        String model = settings.model() == null ? "по умолчанию" : settings.model();
        String effort = settings.reasoningEffort() == null ? "по умолчанию" : settings.reasoningEffort();
        sender.sendMessage("Модель: " + model + "\nМышление: " + effort + "\nВыберите уровень.",
                Collections.nCopies(6, TEXT), List.of("low", "medium", "high", "xhigh", "Назад", "Выход"),
                payloads, List.of(2, 2, 2), false);
    }
}
