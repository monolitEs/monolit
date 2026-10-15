package org.monolites.monolit.handlers.callbacks;

import com.vk.api.sdk.objects.callback.MessageNew;
import lombok.RequiredArgsConstructor;
import org.monolites.monolit.models.dtos.callback.CallbackPayloadEnvelope;
import org.monolites.monolit.services.NewsService;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class NewsCallbackPayloadHandler implements CallbackPayloadHandler<String> {

    public static final CallbackPayloadEnvelope LATEST_NEWS_PAYLOAD =
            new CallbackPayloadEnvelope("news", 1, "latest");

    private final NewsService newsService;

    @Override
    public String type() {
        return LATEST_NEWS_PAYLOAD.type();
    }

    @Override
    public int version() {
        return LATEST_NEWS_PAYLOAD.version();
    }

    @Override
    public Class<String> payloadClass() {
        return String.class;
    }

    @Override
    public void handle(String payload, MessageNew event) {
        if (LATEST_NEWS_PAYLOAD.data().equals(payload)) {
            newsService.publishLatestNews();
        }
    }
}
