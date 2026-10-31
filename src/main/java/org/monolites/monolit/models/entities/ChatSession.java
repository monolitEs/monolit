package org.monolites.monolit.models.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "chat_session")
@Getter
@NoArgsConstructor
public class ChatSession {

    @Id
    @Column(name = "session_key", nullable = false, length = 256)
    private String sessionKey;

    @Column(name = "conversation_id", nullable = false, length = 256)
    private String conversationId;

    public ChatSession(String sessionKey, String conversationId) {
        this.sessionKey = sessionKey;
        this.conversationId = conversationId;
    }
}
