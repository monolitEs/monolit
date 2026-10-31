package org.monolites.monolit.models.dtos.chat;

public record ChatSettingsResponse(String requestId, String conversationId, String model, String reasoningEffort) {
}
