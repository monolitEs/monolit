package org.monolites.monolit.models.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "news_parser_state")
@Getter
@Setter
public class NewsParserState {

    @Id
    @Column(name = "source_key", nullable = false, columnDefinition = "text")
    private String sourceKey;

    @Column(name = "last_pub_date")
    private Instant lastPubDate;
}
