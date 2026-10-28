package org.monolites.monolit.handlers;

import com.vk.api.sdk.client.VkApiClient;
import com.vk.api.sdk.client.actors.GroupActor;
import com.vk.api.sdk.events.longpoll.GroupLongPollApi;
import com.vk.api.sdk.objects.callback.MessageNew;
import org.monolites.monolit.handlers.callbacks.CallbackPayloadDispatcher;
import org.monolites.monolit.handlers.text.TextMessageDispatcher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class GroupLongPoolApiHandler extends GroupLongPollApi {

    private final CallbackPayloadDispatcher callbackPayloadDispatcher;
    private final TextMessageDispatcher textMessageDispatcher;
    private final long ownerId;

    public GroupLongPoolApiHandler(
            VkApiClient client,
            GroupActor actor,
            CallbackPayloadDispatcher callbackPayloadDispatcher,
            TextMessageDispatcher textMessageDispatcher,
            @Value("${VK_MY_ID}") String ownerId
    ) {
        super(client, actor, 25);
        this.callbackPayloadDispatcher = callbackPayloadDispatcher;
        this.textMessageDispatcher = textMessageDispatcher;
        this.ownerId = Long.parseLong(ownerId.trim());
    }

    @Override
    public void messageNew(Integer groupId, MessageNew message) {
        if (message == null || message.getObject() == null || message.getObject().getMessage() == null) {
            return;
        }

        var vkMessage = message.getObject().getMessage();
        if (vkMessage.getFromId() == null || vkMessage.getFromId() != ownerId) {
            return;
        }
        String payload = vkMessage.getPayload();
        if (payload != null && !payload.isBlank()) {
            callbackPayloadDispatcher.dispatch(payload, message);
            return;
        }
        textMessageDispatcher.dispatch(message);
    }
}
