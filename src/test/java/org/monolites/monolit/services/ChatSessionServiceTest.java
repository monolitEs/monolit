package org.monolites.monolit.services;

import org.junit.jupiter.api.Test;
import org.monolites.monolit.models.entities.ChatSession;
import org.monolites.monolit.repositories.ChatSessionRepository;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class ChatSessionServiceTest {

    private final ChatSessionRepository repository = mock(ChatSessionRepository.class);

    @Test
    void generatesInitialIdAndRestoresSavedSelectionWithoutCreatingAnotherChat() {
        Map<String, ChatSession> stored = new HashMap<>();
        when(repository.findById(anyString())).thenAnswer(invocation -> Optional.ofNullable(stored.get(invocation.getArgument(0))));
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> {
            ChatSession state = invocation.getArgument(0);
            stored.put(state.getSessionKey(), state);
            return state;
        });
        var sessions = new ChatSessionService(repository);
        String initialId = sessions.currentConversationId();
        assertThat(UUID.fromString(initialId)).isNotNull();
        assertThat(new ChatSessionService(repository).currentConversationId()).isEqualTo(initialId);
        verify(repository).saveAndFlush(any());

        sessions.startNewConversation();
        String firstId = sessions.currentConversationId();
        assertThat(UUID.fromString(firstId)).isNotNull();
        assertThat(firstId).isNotEqualTo(initialId);
        assertThat(new ChatSessionService(repository).currentConversationId()).isEqualTo(firstId);

        sessions.startNewConversation();
        assertThat(sessions.currentConversationId()).isNotEqualTo(firstId);
        assertThat(new ChatSessionService(repository).currentConversationId()).isEqualTo(sessions.currentConversationId());
    }

    @Test
    void doesNotPublishAnIdWhoseSaveFailed() {
        when(repository.findById("main")).thenReturn(Optional.of(new ChatSession("main", "saved")));
        when(repository.saveAndFlush(any())).thenThrow(new DataAccessResourceFailureException("fixture"));
        var sessions = new ChatSessionService(repository);

        assertThatThrownBy(sessions::startNewConversation).isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(sessions.currentConversationId()).isEqualTo("saved");
    }

    @Test
    void initialSaveFailureDoesNotCreateAnUnpersistedSession() {
        when(repository.saveAndFlush(any())).thenThrow(new DataAccessResourceFailureException("fixture"));

        assertThatThrownBy(() -> new ChatSessionService(repository)).isInstanceOf(DataAccessResourceFailureException.class);
    }
}
