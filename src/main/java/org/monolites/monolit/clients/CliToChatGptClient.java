package org.monolites.monolit.clients;

import org.monolites.monolit.models.dtos.chat.ChatRequest;
import org.monolites.monolit.models.dtos.chat.ChatResponse;
import org.monolites.monolit.models.dtos.chat.ChatSettingsRequest;
import org.monolites.monolit.models.dtos.chat.ChatSettingsResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.PostExchange;
import org.springframework.web.service.annotation.PutExchange;

public interface CliToChatGptClient {

    @PostExchange("/api/chat")
    ResponseEntity<ChatResponse> chat(@RequestBody ChatRequest request);

    @GetExchange("/api/chat/settings")
    ResponseEntity<ChatSettingsResponse> getSettings(@RequestParam("conversationId") String conversationId);

    @PutExchange("/api/chat/settings")
    ResponseEntity<ChatSettingsResponse> updateSettings(@RequestParam("conversationId") String conversationId,
                                                        @RequestBody ChatSettingsRequest request);
}
