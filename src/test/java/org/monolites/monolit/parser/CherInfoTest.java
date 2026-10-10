package org.monolites.monolit.parser;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.monolites.monolit.models.dtos.NewsData;
import org.monolites.monolit.models.exception.NewsParseException;
import org.monolites.monolit.models.entities.NewsParserState;
import org.monolites.monolit.repositories.NewsParserStateRepository;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Date;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;

class CherInfoTest {

    private static final byte[] IMAGE = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jwZkAAAAASUVORK5CYII=");

    private final AtomicReference<String> rss = new AtomicReference<>();
    private final AtomicInteger articleRequests = new AtomicInteger();
    private final Map<String, String> pages = new HashMap<>();
    private final List<NewsData> parsedNews = new ArrayList<>();
    private final AtomicReference<NewsParserState> storedState = new AtomicReference<>();
    private final Path imagesDirectory = Path.of("images").toAbsolutePath();
    private HttpServer server;
    private Parser parser;
    private String baseUrl;
    private boolean imagesDirectoryExisted;
    private NewsParserStateRepository stateRepository;

    @BeforeEach
    void setUp() throws IOException {
        imagesDirectoryExisted = Files.exists(imagesDirectory);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/", this::respond);
        server.start();
        stateRepository = mock(NewsParserStateRepository.class);
        when(stateRepository.findById("cherinfo")).thenAnswer(invocation -> Optional.ofNullable(storedState.get()));
        when(stateRepository.save(any(NewsParserState.class))).thenAnswer(invocation -> {
            NewsParserState state = invocation.getArgument(0);
            storedState.set(state);
            return state;
        });
        restartParser();
    }

    @AfterEach
    void tearDown() throws IOException {
        server.stop(0);
        for (NewsData item : parsedNews) {
            if (item.getImages() != null) {
                for (File image : item.getImages().values()) {
                    Files.deleteIfExists(image.toPath());
                }
            }
        }
        if (!imagesDirectoryExisted && Files.isDirectory(imagesDirectory)) {
            boolean empty;
            try (var contents = Files.list(imagesDirectory)) {
                empty = contents.findAny().isEmpty();
            }
            if (empty) {
                Files.delete(imagesDirectory);
            }
        }
    }

    @Test
    void readsRssAndArticleThroughParserInterfaceAndDownloadsGallery() throws IOException {
        rss.set(feed(2, 1));
        pages.put("/news/1", """
                <div class="article-text"><p>First &amp; second.</p>
                <p><iframe src="/video"></iframe>Embedded video caption.</p>
                <p>Last paragraph.</p><div class="fotorama">
                <a href="%s/image/1"><img src="/thumbnail"></a>
                <a href="%s/image/2"></a></div></div><p>Outside article.</p>
                """.formatted(baseUrl, baseUrl));

        List<NewsData> news = parse();

        assertThat(parser.getName()).isEqualTo("ЧерИнфо");
        assertThat(news).extracting(NewsData::getTitle).containsExactly("News 1", "News 2");
        assertThat(news.getFirst().getDate()).isEqualTo(date(1));
        assertThat(news.getFirst().getDescription()).isEqualTo("First & second.\n\nLast paragraph.\n\n");
        assertThat(news.getFirst().getImages()).hasSize(2);
        for (File image : news.getFirst().getImages().values()) {
            assertThat(image.getName()).endsWith(".png");
            assertThat(Files.readAllBytes(image.toPath())).isEqualTo(IMAGE);
        }
    }

    @Test
    void skipsUnchangedFeedAndOnlyReadsStrictlyNewerArticles() {
        rss.set(feed(2, 1));
        assertThat(parse()).extracting(NewsData::getTitle).containsExactly("News 1", "News 2");
        assertThat(parse()).isEmpty();
        assertThat(articleRequests).hasValue(2);

        rss.set(feed(3, 2, 1));

        assertThat(parse()).extracting(NewsData::getTitle).containsExactly("News 3");
        assertThat(articleRequests).hasValue(3);
        verify(stateRepository, times(1)).findById("cherinfo");
        assertThat(storedState.get().getLastPubDate()).isEqualTo(date(3));
    }

    @Test
    void readsOnlyFiveLatestEntriesOnFirstRun() {
        rss.set(feed(7, 6, 5, 4, 3, 2, 1));

        assertThat(parse()).extracting(NewsData::getTitle)
                .containsExactly("News 3", "News 4", "News 5", "News 6", "News 7");
        assertThat(storedState.get().getLastPubDate()).isEqualTo(date(7));
    }

    @Test
    void downloadsInlineImageAndRestoresCheckpointAfterRestart() throws IOException {
        rss.set(feed(1));
        pages.put("/news/1", "<div class='article-text'><p>Inline photo.<img src='" + baseUrl + "/image/1'></p></div>");
        NewsData item = parse().getFirst();

        assertThat(item.getDescription()).isEqualTo("Inline photo.\n\n");
        assertThat(item.getImages()).hasSize(1);
        assertThat(Files.readAllBytes(item.getImages().values().iterator().next().toPath())).isEqualTo(IMAGE);
        assertThat(parse()).isEmpty();

        restartParser();
        assertThat(parse()).isEmpty();
        assertThat(articleRequests).hasValue(1);
        verify(stateRepository, times(2)).findById("cherinfo");
    }

    @Test
    void reportsArticleHttpFailureUsingSourceException() {
        rss.set(feed(1));
        pages.put("/news/1", null);

        assertThatThrownBy(this::parse).isInstanceOf(NewsParseException.class)
                .hasCauseInstanceOf(IOException.class);
        // Keep the original checkpoint timing: selection precedes article parsing.
        assertThat(storedState.get().getLastPubDate()).isEqualTo(date(1));
        assertThat(ReflectionTestUtils.getField(parser, "lastPubDate")).isEqualTo(Date.from(date(1)));
    }

    @Test
    void readsAllNewEntriesEvenWhenMoreThanFiveAppeared() {
        storeCheckpoint(date(1));
        rss.set(feed(8, 7, 6, 5, 4, 3, 2, 1));

        assertThat(parse()).extracting(NewsData::getTitle)
                .containsExactly("News 2", "News 3", "News 4", "News 5", "News 6", "News 7", "News 8");
    }

    @Test
    void keepsMemoryCheckpointWhenDatabaseHasAnOlderValue() {
        storeCheckpoint(date(1));
        ReflectionTestUtils.setField(parser, "lastPubDate", Date.from(date(3)));
        rss.set(feed(4, 3, 2, 1));

        assertThat(parse()).extracting(NewsData::getTitle).containsExactly("News 4");
        verify(stateRepository, never()).findById("cherinfo");
    }

    @Test
    void treatsNullDateInDatabaseAsFirstRun() {
        storeCheckpoint(null);
        rss.set(feed(6, 5, 4, 3, 2, 1));

        assertThat(parse()).extracting(NewsData::getTitle)
                .containsExactly("News 2", "News 3", "News 4", "News 5", "News 6");
    }

    @Test
    void doesNotParseOrResetStateWhenDatabaseReadFails() {
        when(stateRepository.findById("cherinfo")).thenThrow(new IllegalStateException("Database unavailable"));

        assertThatThrownBy(this::parse).hasMessage("Database unavailable");
        assertThat(articleRequests).hasValue(0);
        assertThat(ReflectionTestUtils.getField(parser, "lastPubDate")).isNull();
    }

    @Test
    void databaseWriteFailureKeepsBothCheckpoints() {
        storeCheckpoint(date(1));
        rss.set(feed(2, 1));
        when(stateRepository.save(any(NewsParserState.class))).thenThrow(new IllegalStateException("Write failure"));

        assertThatThrownBy(this::parse).hasMessage("Write failure");

        assertThat(storedState.get().getLastPubDate()).isEqualTo(date(1));
        assertThat(ReflectionTestUtils.getField(parser, "lastPubDate")).isEqualTo(Date.from(date(1)));
        assertThat(articleRequests).hasValue(0);
    }

    private void storeCheckpoint(Instant date) {
        NewsParserState state = new NewsParserState();
        state.setSourceKey("cherinfo");
        state.setLastPubDate(date);
        storedState.set(state);
    }

    private void restartParser() {
        parser = new CherInfo(stateRepository);
        ReflectionTestUtils.setField(parser, "rssLink", baseUrl + "/rss");
    }

    private List<NewsData> parse() {
        List<NewsData> result = parser.parseData();
        parsedNews.addAll(result);
        return result;
    }

    private String feed(int... ids) {
        StringBuilder entries = new StringBuilder();
        for (int id : ids) {
            entries.append("<item><title>News %d</title><link>%s/news/%d</link><pubDate>%s</pubDate></item>"
                    .formatted(id, baseUrl, id, DateTimeFormatter.RFC_1123_DATE_TIME.format(date(id).atZone(ZoneOffset.UTC))));
        }
        return "<rss version='2.0'><channel><title>Test feed</title><link>%s</link><description>Test</description>%s</channel></rss>"
                .formatted(baseUrl, entries);
    }

    private static Instant date(int id) {
        return Instant.parse("2026-10-10T09:00:00Z").plusSeconds(id * 60L);
    }

    private void respond(HttpExchange exchange) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getPath();
            byte[] body;
            int status = 200;
            if (path.equals("/rss")) {
                exchange.getResponseHeaders().set("Content-Type", "application/rss+xml; charset=UTF-8");
                body = rss.get().getBytes(StandardCharsets.UTF_8);
            } else if (path.startsWith("/image/")) {
                exchange.getResponseHeaders().set("Content-Type", "image/png");
                body = IMAGE;
            } else {
                articleRequests.incrementAndGet();
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
                String page = pages.getOrDefault(path, "<div class='article-text'><p>Article text.</p></div>");
                status = page == null ? 503 : 200;
                body = (page == null ? "Unavailable" : page).getBytes(StandardCharsets.UTF_8);
            }
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
        }
    }
}
