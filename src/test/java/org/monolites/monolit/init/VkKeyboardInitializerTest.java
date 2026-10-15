package org.monolites.monolit.init;

import org.junit.jupiter.api.Test;
import org.monolites.monolit.services.VkMessageSenderService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class VkKeyboardInitializerTest {

    @Test
    void enablesStartupKeyboardByDefault() {
        new ApplicationContextRunner()
                .withUserConfiguration(VkKeyboardInitializer.class)
                .withBean(VkMessageSenderService.class, () -> mock(VkMessageSenderService.class))
                .run(context -> assertThat(context).hasSingleBean(VkKeyboardInitializer.class));
    }

    @Test
    void doesNotSendKeyboardWhenVkIsDisabled() {
        VkMessageSenderService sender = mock(VkMessageSenderService.class);
        new ApplicationContextRunner()
                .withUserConfiguration(VkKeyboardInitializer.class)
                .withBean(VkMessageSenderService.class, () -> sender)
                .withPropertyValues("monolit.vk.long-poll.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(VkKeyboardInitializer.class);
                    verifyNoInteractions(sender);
                });
    }
}
