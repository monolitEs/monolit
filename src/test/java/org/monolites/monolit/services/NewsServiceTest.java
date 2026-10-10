package org.monolites.monolit.services;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.monolites.monolit.models.dtos.NewsData;
import org.monolites.monolit.parser.Parser;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class NewsServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-10T09:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneId.of("Europe/Moscow"));
    private VkMessageSenderService sender;

    @BeforeEach
    void setUp() {
        sender = mock(VkMessageSenderService.class);
    }

    @Test
    void springInjectsMultipleParserImplementationsIntoCommonPublisher() {
        Parser first = new Source("ЧерИнфо", List.of(news("First", "First body.")));
        NewsData secondNews = news("Second", "Second body.");
        secondNews.setDate(null);
        Parser second = new Source("Another site", List.of(secondNews));
        new ApplicationContextRunner()
                .withBean("firstSource", Parser.class, () -> first)
                .withBean("secondSource", Parser.class, () -> second)
                .withBean(VkMessageSenderService.class, () -> sender)
                .withBean(Clock.class, () -> CLOCK)
                .withBean(NewsService.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    context.getBean(NewsService.class).publishLatestNews();
                });

        ArgumentCaptor<String> messages = ArgumentCaptor.forClass(String.class);
        verify(sender, times(2)).sendMessage(messages.capture());
        assertThat(messages.getAllValues().stream().map(message -> message.replace("\r\n", "\n")).toList()).containsExactly(
                "Новость ЧерИнфо\n\nFirst\n10.10.2026 12:00\n\nFirst body.",
                "Новость Another site\n\nSecond\nДата не указана\n\nSecond body.");
    }

    @Test
    void skipsEmptyArticlesAndAllowsEmptyParserCollection() {
        new NewsService(List.of(), sender, CLOCK).publishLatestNews();
        new NewsService(List.of(new Source("Empty", List.of(new NewsData()))), sender, CLOCK)
                .publishLatestNews();

        verifyNoInteractions(sender);
    }

    @Test
    void sendsAtMostTenImagesSplitsFullTextAndDeletesAllDownloadedFiles(@TempDir Path directory) throws Exception {
        NewsData item = news("Long article", "x".repeat(8_000));
        Map<String, File> images = images(directory, 12);
        item.setImages(images);

        new NewsService(List.of(new Source("Site", List.of(item))), sender, CLOCK).publishLatestNews();

        ArgumentCaptor<String> attachmentMessage = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map<String, File>> attachments = mapCaptor();
        ArgumentCaptor<String> continuationMessages = ArgumentCaptor.forClass(String.class);
        verify(sender).sendMessage(attachmentMessage.capture(), attachments.capture());
        verify(sender, times(3)).sendMessage(continuationMessages.capture());
        assertThat(attachments.getValue()).hasSize(10);
        List<String> parts = new ArrayList<>(List.of(attachmentMessage.getValue()));
        parts.addAll(continuationMessages.getAllValues());
        assertThat(parts).allSatisfy(part -> assertThat(part).hasSizeLessThanOrEqualTo(3_900));
        assertThat(parts.stream().mapToLong(part -> part.chars().filter(character -> character == 'x').count()).sum())
                .isEqualTo(8_000);
        for (File image : images.values()) {
            assertThat(image).doesNotExist();
        }
    }

    @Test
    void keepsPhotoToTextFallbackAndCleansFiles(@TempDir Path directory) throws Exception {
        NewsData item = news("Photo article", "Body.");
        item.setImages(images(directory, 1));
        doThrow(new IllegalStateException("Photo failure")).when(sender).sendMessage(anyString(), anyMap());

        new NewsService(List.of(new Source("Site", List.of(item))), sender, CLOCK).publishLatestNews();

        verify(sender).sendMessage(anyString(), anyMap());
        verify(sender).sendMessage(contains("Photo article"));
        assertThat(item.getImages().values()).allSatisfy(image -> assertThat(image).doesNotExist());
    }

    @Test
    void stopsFailedSourceBatchButContinuesOtherParserAndCleansUnsentImages(@TempDir Path directory) throws Exception {
        NewsData first = news("Failing article", "Body.");
        NewsData unsent = news("Unsent article", "Body.");
        unsent.setImages(images(directory, 1));
        doThrow(new IllegalStateException("Text failure")).when(sender).sendMessage(contains("Failing article"));
        List<Parser> sources = List.of(
                new Source("First source", List.of(first, unsent)),
                new Source("Second source", List.of(news("Other article", "Body."))));

        new NewsService(sources, sender, CLOCK).publishLatestNews();

        verify(sender, never()).sendMessage(contains("Unsent article"), anyMap());
        verify(sender).sendMessage(contains("Other article"));
        assertThat(unsent.getImages().values()).allSatisfy(image -> assertThat(image).doesNotExist());
    }

    @Test
    void continuesAnotherParserAfterParsingFailure() {
        Parser failingSource = new Parser() {
            @Override
            public List<NewsData> parseData() {
                throw new IllegalStateException("RSS unavailable");
            }

            @Override
            public String getName() {
                return "Unavailable source";
            }
        };

        new NewsService(List.of(failingSource, new Source("Available", List.of(news("Article", "Body.")))),
                sender, CLOCK).publishLatestNews();

        verify(sender).sendMessage(contains("Новость Available"));
    }

    private static NewsData news(String title, String description) {
        NewsData item = new NewsData();
        item.setTitle(title);
        item.setDescription(description);
        item.setDate(Date.from(NOW));
        return item;
    }

    private static Map<String, File> images(Path directory, int count) throws Exception {
        Map<String, File> images = new LinkedHashMap<>();
        for (int index = 0; index < count; index++) {
            Path path = directory.resolve("image-" + index + ".jpg");
            Files.writeString(path, "test-image");
            images.put(path.getFileName().toString(), path.toFile());
        }
        return images;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentCaptor<Map<String, File>> mapCaptor() {
        return ArgumentCaptor.forClass((Class) Map.class);
    }

    private record Source(String name, List<NewsData> items) implements Parser {
        @Override
        public List<NewsData> parseData() {
            return items;
        }

        @Override
        public String getName() {
            return name;
        }
    }
}
