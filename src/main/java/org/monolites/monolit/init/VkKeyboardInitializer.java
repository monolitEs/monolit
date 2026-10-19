package org.monolites.monolit.init;

import lombok.RequiredArgsConstructor;
import org.monolites.monolit.services.VkMessageSenderService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.vk.api.sdk.objects.messages.KeyboardButtonActionTextType.TEXT;
import static org.monolites.monolit.handlers.callbacks.NewsCallbackPayloadHandler.LATEST_NEWS_PAYLOAD;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "monolit.vk.long-poll.enabled", havingValue = "true", matchIfMissing = true)
public class VkKeyboardInitializer implements CommandLineRunner {

    private final VkMessageSenderService messageSender;

    @Override
    public void run(String... args) {
        messageSender.sendMessage("Бот готов.", List.of(TEXT),
                List.of("Получить новости"), List.of(LATEST_NEWS_PAYLOAD), false);
    }
}
