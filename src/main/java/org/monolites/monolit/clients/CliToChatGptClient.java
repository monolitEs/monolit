package org.monolites.monolit.clients;

import org.monolites.monolit.models.dtos.chat.ChatRequest;
import org.monolites.monolit.models.dtos.chat.ChatResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

public interface CliToChatGptClient {

    @PostExchange("/api/chat")
    ResponseEntity<ChatResponse> chat(@RequestBody ChatRequest request);
}
