package org.monolites.monolit.handlers.text;

import com.vk.api.sdk.objects.callback.MessageNew;
import lombok.extern.slf4j.Slf4j;
import org.monolites.monolit.services.DialogueModeService;
import org.monolites.monolit.services.DialogueModeService.Mode;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class TextMessageDispatcher {

    private final DialogueModeService modeService;
    private final Map<Mode, TextMessageHandler> handlers;

    public TextMessageDispatcher(DialogueModeService modeService, List<TextMessageHandler> handlers) {
        this.modeService = modeService;
        Map<Mode, TextMessageHandler> handlersByMode = new EnumMap<>(Mode.class);
        for (TextMessageHandler handler : handlers) {
            Mode mode = handler.mode();
            if (handlersByMode.putIfAbsent(mode, handler) != null) {
                throw new IllegalStateException("Дублирование обработчиков текста mode=" + mode);
            }
        }
        this.handlers = Map.copyOf(handlersByMode);
    }

    // Receives structurally valid, owner-checked events without a payload.
    public void dispatch(MessageNew event) {
        String text = event.getObject().getMessage().getText();
        if (text == null || text.isBlank()) {
            return;
        }
        Mode mode = modeService.currentMode();
        TextMessageHandler handler = handlers.get(mode);
        if (handler != null) {
            try {
                handler.handle(event);
            } catch (RuntimeException e) {
                log.error("Ошибка обработчика текста mode={}", mode, e);
            }
        }
    }
}
