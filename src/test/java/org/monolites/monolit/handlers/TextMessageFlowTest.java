package org.monolites.monolit.handlers;

import com.vk.api.sdk.client.VkApiClient;
import com.vk.api.sdk.client.actors.GroupActor;
import com.vk.api.sdk.objects.callback.MessageNew;
import com.vk.api.sdk.objects.callback.MessageObject;
import com.vk.api.sdk.objects.messages.Message;
import org.junit.jupiter.api.Test;
import org.monolites.monolit.configs.JacksonConfig;
import org.monolites.monolit.handlers.callbacks.CallbackPayloadDispatcher;
import org.monolites.monolit.handlers.callbacks.NewsCallbackPayloadHandler;
import org.monolites.monolit.handlers.text.TextMessageDispatcher;
import org.monolites.monolit.handlers.text.TextMessageHandler;
import org.monolites.monolit.services.DialogueModeService;
import org.monolites.monolit.services.NewsService;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.monolites.monolit.services.DialogueModeService.Mode.CHATGPT;
import static org.monolites.monolit.services.DialogueModeService.Mode.DEFAULT;

class TextMessageFlowTest {

    @Test
    void routesOwnerTextByModeWithoutFallingBackFromUnknownOrMalformedPayloads() {
        DialogueModeService modes = new DialogueModeService();
        TextMessageHandler textHandler = mock(TextMessageHandler.class);
        when(textHandler.mode()).thenReturn(CHATGPT);
        TextMessageDispatcher texts = new TextMessageDispatcher(modes, List.of(textHandler));
        NewsService news = mock(NewsService.class);
        CallbackPayloadDispatcher callbacks = new CallbackPayloadDispatcher(
                new JacksonConfig().objectMapper(), List.of(new NewsCallbackPayloadHandler(news)));
        GroupLongPoolApiHandler handler = new GroupLongPoolApiHandler(
                mock(VkApiClient.class), mock(GroupActor.class), callbacks, texts, "42");

        handler.messageNew(1, event(42L, "Default text", null));
        modes.setMode(CHATGPT);
        handler.messageNew(1, event(99L, "Another user's text", null));
        handler.messageNew(1, event(42L, "Unknown action", "{\"type\":\"unknown\",\"version\":1,\"data\":null}"));
        handler.messageNew(1, event(42L, "Malformed action", "not-json"));
        handler.messageNew(1, event(42L, "Получить новости", "{\"type\":\"news\",\"version\":1,\"data\":\"latest\"}"));
        MessageNew question = event(42L, "Question", null);
        handler.messageNew(1, question);
        modes.setMode(DEFAULT);
        handler.messageNew(1, event(42L, "After leaving", null));

        verify(textHandler).handle(question);
        verify(textHandler, times(1)).handle(any());
        verify(news).publishLatestNews();
        assertThat(modes.currentMode()).isEqualTo(DEFAULT);
    }

    private static MessageNew event(long fromId, String text, String payload) {
        return new MessageNew().setObject(new MessageObject().setMessage(
                new Message().setFromId(fromId).setText(text).setPayload(payload)));
    }
}
