package org.monolites.monolit.repositories;

import org.monolites.monolit.models.entities.NewsParserState;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NewsParserStateRepository extends JpaRepository<NewsParserState, String> {
}
