package org.monolites.monolit.repositories;

import org.monolites.monolit.models.entities.ChatSession;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatSessionRepository extends JpaRepository<ChatSession, String> {
}
