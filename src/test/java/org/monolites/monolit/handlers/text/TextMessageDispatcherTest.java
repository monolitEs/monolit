package org.monolites.monolit.handlers.text;

import com.vk.api.sdk.objects.callback.MessageNew;
import com.vk.api.sdk.objects.callback.MessageObject;
import com.vk.api.sdk.objects.messages.Message;
import org.junit.jupiter.api.Test;
import org.monolites.monolit.services.DialogueModeService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import static org.monolites.monolit.services.DialogueModeService.Mode.CHATGPT;
import static org.monolites.monolit.services.DialogueModeService.Mode.DEFAULT;

class TextMessageDispatcherTest {

    @Test
    void startsWithoutHandlersAndIgnoresTextInAnyMode() {
        new ApplicationContextRunner()
                .withUserConfiguration(DialogueModeService.class, TextMessageDispatcher.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(TextMessageHandler.class)).isEmpty();
                    DialogueModeService modes = context.getBean(DialogueModeService.class);
                    assertThat(modes.currentMode()).isEqualTo(DEFAULT);
                    TextMessageDispatcher dispatcher = context.getBean(TextMessageDispatcher.class);
                    dispatcher.dispatch(event("Text"));
                    modes.setMode(CHATGPT);
                    dispatcher.dispatch(event("Text"));
                });
    }

    @Test
    void discoversHandlerBeanAndRoutesOnlyWhileItsModeIsActive() {
        TextMessageHandler handler = handlerFor(CHATGPT);
        MessageNew question = event("Hello");

        new ApplicationContextRunner()
                .withUserConfiguration(DialogueModeService.class, TextMessageDispatcher.class)
                .withBean(TextMessageHandler.class, () -> handler)
                .run(context -> {
                    DialogueModeService modes = context.getBean(DialogueModeService.class);
                    TextMessageDispatcher dispatcher = context.getBean(TextMessageDispatcher.class);
                    dispatcher.dispatch(event("Before entering"));
                    modes.setMode(CHATGPT);
                    dispatcher.dispatch(question);
                    modes.setMode(DEFAULT);
                    dispatcher.dispatch(event("After leaving"));
                    verify(handler).handle(question);
                    verify(handler, times(1)).handle(any());
                });

        assertThat(new DialogueModeService().currentMode()).isEqualTo(DEFAULT);
    }

    @Test
    void ignoresMessagesWithoutTextEvenWhenHandlerIsRegistered() {
        DialogueModeService modes = new DialogueModeService();
        modes.setMode(CHATGPT);
        TextMessageHandler handler = handlerFor(CHATGPT);
        TextMessageDispatcher dispatcher = new TextMessageDispatcher(modes, List.of(handler));

        dispatcher.dispatch(event(null));
        dispatcher.dispatch(event(""));
        dispatcher.dispatch(event(" \n\t"));

        verify(handler, never()).handle(any());
    }

    @Test
    void rejectsDuplicateModesDuringContextStartup() {
        TextMessageHandler first = handlerFor(CHATGPT);
        TextMessageHandler second = handlerFor(CHATGPT);

        new ApplicationContextRunner()
                .withUserConfiguration(DialogueModeService.class, TextMessageDispatcher.class)
                .withBean("first", TextMessageHandler.class, () -> first)
                .withBean("second", TextMessageHandler.class, () -> second)
                .run(context -> assertThat(context.getStartupFailure())
                        .hasRootCauseInstanceOf(IllegalStateException.class)
                        .hasStackTraceContaining("Дублирование обработчиков текста mode=CHATGPT"));
    }

    @Test
    void handlerFailureDoesNotPreventRoutingTheNextMessage() {
        DialogueModeService modes = new DialogueModeService();
        modes.setMode(CHATGPT);
        TextMessageHandler handler = handlerFor(CHATGPT);
        TextMessageDispatcher dispatcher = new TextMessageDispatcher(modes, List.of(handler));
        MessageNew failedMessage = event("First");
        MessageNew nextMessage = event("Next");
        doThrow(new IllegalStateException("Fixture failure")).when(handler).handle(failedMessage);

        dispatcher.dispatch(failedMessage);
        dispatcher.dispatch(nextMessage);

        verify(handler).handle(nextMessage);
        assertThat(modes.currentMode()).isEqualTo(CHATGPT);
    }

    @Test
    void rejectsNullModeWithoutLosingCurrentMode() {
        DialogueModeService modes = new DialogueModeService();
        modes.setMode(CHATGPT);

        assertThatThrownBy(() -> modes.setMode(null)).isInstanceOf(NullPointerException.class);
        assertThat(modes.currentMode()).isEqualTo(CHATGPT);
    }

    private static TextMessageHandler handlerFor(DialogueModeService.Mode mode) {
        TextMessageHandler handler = mock(TextMessageHandler.class);
        when(handler.mode()).thenReturn(mode);
        return handler;
    }

    private static MessageNew event(String text) {
        return new MessageNew().setObject(new MessageObject().setMessage(
                new Message().setFromId(42L).setText(text)));
    }
}
