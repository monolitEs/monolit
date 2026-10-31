package org.monolites.monolit.services;

import org.monolites.monolit.models.entities.ChatSession;
import org.monolites.monolit.repositories.ChatSessionRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@ConditionalOnProperty(name = "monolit.chat.enabled", havingValue = "true")
public class ChatSessionService {

    private static final String SESSION_KEY = "main";
    private final ChatSessionRepository repository;
    private volatile String conversationId;

    public ChatSessionService(ChatSessionRepository repository) {
        this.repository = repository;
        this.conversationId = repository.findById(SESSION_KEY)
                .map(ChatSession::getConversationId).orElseGet(this::createConversation);
    }

    public String currentConversationId() {
        return conversationId;
    }

    public void startNewConversation() {
        conversationId = createConversation();
    }

    private String createConversation() {
        String nextId = UUID.randomUUID().toString();
        repository.saveAndFlush(new ChatSession(SESSION_KEY, nextId));
        return nextId;
    }
}
