package org.monolites.monolit.handlers.text;

import com.vk.api.sdk.objects.callback.MessageNew;
import lombok.RequiredArgsConstructor;
import org.monolites.monolit.services.DialogueModeService.Mode;
import org.monolites.monolit.services.VkChatService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "monolit.chat.enabled", havingValue = "true")
public class ChatGptTextMessageHandler implements TextMessageHandler {

    private final VkChatService chat;

    @Override
    public Mode mode() {
        return Mode.CHATGPT;
    }

    @Override
    public void handle(MessageNew event) {
        chat.ask(event);
    }
}
