package org.monolites.monolit.services;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.monolites.monolit.models.entities.ChatSession;
import org.monolites.monolit.repositories.ChatSessionRepository;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfEnvironmentVariable(named = "CHAT_SMOKE_URL", matches = ".+")
class ChatSessionPersistenceTest {

    private static ConfigurableApplicationContext open() {
        var context = new AnnotationConfigApplicationContext();
        TestPropertyValues.of("spring.datasource.url=" + System.getenv("CHAT_SMOKE_URL"),
                        "spring.datasource.username=smoke", "spring.datasource.password=smoke",
                        "spring.jpa.hibernate.ddl-auto=update", "spring.datasource.hikari.maximum-pool-size=2")
                .applyTo(context);
        context.register(PersistenceConfig.class);
        context.refresh();
        return context;
    }

    @Test
    void restoresAfterContextRestartAndKeepsSelectionWhenDatabaseRejectsSwitch() {
        String selected;
        try (var context = open()) {
            var sessions = new ChatSessionService(context.getBean(ChatSessionRepository.class));
            assertThat(java.util.UUID.fromString(sessions.currentConversationId())).isNotNull();
            sessions.startNewConversation();
            selected = sessions.currentConversationId();
        }
        try (var context = open()) {
            var repository = context.getBean(ChatSessionRepository.class);
            var sessions = new ChatSessionService(repository);
            assertThat(sessions.currentConversationId()).isEqualTo(selected);
            sessions.startNewConversation();
            assertThat(sessions.currentConversationId()).isNotEqualTo(selected);
            selected = sessions.currentConversationId();

            var jdbc = context.getBean(JdbcTemplate.class);
            assertThat(jdbc.queryForObject("select count(*) from chat_session", Long.class)).isEqualTo(1L);
            jdbc.execute("alter table chat_session add constraint smoke_reject_switch check (conversation_id = '" + selected + "')");
            try {
                assertThatThrownBy(sessions::startNewConversation).isInstanceOf(DataIntegrityViolationException.class);
                assertThat(sessions.currentConversationId()).isEqualTo(selected);
                assertThat(repository.findById("main").orElseThrow().getConversationId()).isEqualTo(selected);
            } finally {
                jdbc.execute("alter table chat_session drop constraint smoke_reject_switch");
            }
        }
        try (var context = open()) {
            assertThat(new ChatSessionService(context.getBean(ChatSessionRepository.class))
                    .currentConversationId()).isEqualTo(selected);
        }
    }

    // Explicit registration keeps this configuration outside application component scans.
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = ChatSession.class)
    @EnableJpaRepositories(basePackageClasses = ChatSessionRepository.class)
    static class PersistenceConfig {
    }
}
