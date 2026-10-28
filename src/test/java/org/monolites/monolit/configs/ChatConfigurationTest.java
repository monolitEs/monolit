package org.monolites.monolit.configs;

import org.junit.jupiter.api.Test;
import org.monolites.monolit.clients.CliToChatGptClient;
import org.monolites.monolit.handlers.callbacks.ChatCallbackPayloadHandler;
import org.monolites.monolit.handlers.text.ChatGptTextMessageHandler;
import org.monolites.monolit.handlers.text.TextMessageDispatcher;
import org.monolites.monolit.init.VkKeyboardInitializer;
import org.monolites.monolit.services.DialogueModeService;
import org.monolites.monolit.services.VkChatService;
import org.monolites.monolit.services.VkMessageSenderService;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.autoconfigure.web.client.RestClientAutoConfiguration;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ChatConfigurationTest {

    private final VkMessageSenderService sender = mock(VkMessageSenderService.class);
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class, RestClientAutoConfiguration.class))
            .withInitializer(context -> context.getBeanFactory()
                    .setConversionService(ApplicationConversionService.getSharedInstance()))
            .withUserConfiguration(JacksonConfig.class, DialogueModeService.class, TextMessageDispatcher.class,
                    ChatClientConfig.class, VkChatService.class, ChatCallbackPayloadHandler.class,
                    ChatGptTextMessageHandler.class, VkKeyboardInitializer.class)
            .withBean(VkMessageSenderService.class, () -> sender);

    @Test
    void disabledChatOverridesApplicationDefaultAndKeepsNewsOnlyKeyboard() {
        runner.withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("monolit.chat.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(CliToChatGptClient.class);
                    assertThat(context).doesNotHaveBean(VkChatService.class);
                    assertThat(context).doesNotHaveBean(ChatCallbackPayloadHandler.class);
                    assertThat(context).doesNotHaveBean(ChatGptTextMessageHandler.class);
                    verifyNoInteractions(sender);
                    context.getBean(VkKeyboardInitializer.class).run();
                    verify(sender).sendMessage(eq("Бот готов."), anyList(), eq(java.util.List.of("Получить новости")),
                            anyList(), eq(false));
                });
    }

    @Test
    void enabledChatWiresHandlersAndStartupKeyboardWithoutRequestingModel() {
        runner.withPropertyValues("monolit.chat.enabled=true", "VK_MY_ID=42",
                        "monolit.chat.base-url=http://127.0.0.1:1", "monolit.chat.conversation-id=fixture")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(CliToChatGptClient.class);
                    assertThat(context).hasSingleBean(VkChatService.class);
                    assertThat(context).hasSingleBean(ChatGptTextMessageHandler.class);
                    assertThat(context).hasSingleBean(ChatCallbackPayloadHandler.class);
                    verifyNoInteractions(sender);
                    context.getBean(VkKeyboardInitializer.class).run();
                    verify(sender).sendMessage(eq("Бот готов."), anyList(),
                            eq(java.util.List.of("Получить новости", "ChatGPT")), anyList(), eq(false));
                });
    }

    @Test
    void chatEnabledByDefaultFromApplicationProperties() {
        runner.withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("VK_MY_ID=42")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(CliToChatGptClient.class);
                    assertThat(context).hasSingleBean(VkChatService.class);
                    verifyNoInteractions(sender);
                });
    }

    @Test
    void enabledChatRequiresConfiguration() {
        runner.withPropertyValues("monolit.chat.enabled=true", "VK_MY_ID=42",
                        "monolit.chat.conversation-id=fixture")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("monolit.chat.enabled=true", "VK_MY_ID=42",
                        "monolit.chat.base-url=http://127.0.0.1:1")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void enabledChatRejectsInvalidConversationId() {
        runner.withPropertyValues("monolit.chat.enabled=true", "VK_MY_ID=42",
                        "monolit.chat.base-url=http://127.0.0.1:1", "monolit.chat.conversation-id= ")
                .run(context -> assertThat(context.getStartupFailure())
                        .hasRootCauseInstanceOf(IllegalArgumentException.class));
    }
}
