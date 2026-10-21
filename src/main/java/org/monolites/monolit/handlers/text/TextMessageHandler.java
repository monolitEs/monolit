package org.monolites.monolit.handlers.text;

import com.vk.api.sdk.objects.callback.MessageNew;
import org.monolites.monolit.services.DialogueModeService.Mode;

public interface TextMessageHandler {

    Mode mode();

    void handle(MessageNew event);
}
