package org.monolites.monolit.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.monolites.monolit.models.dtos.NewsData;
import org.monolites.monolit.parser.Parser;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class NewsService {

    private static final int MAX_IMAGE_COUNT = 10;
    private static final int MAX_MESSAGE_LENGTH = 3_900;
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private final List<Parser> parsers;
    private final VkMessageSenderService messageSender;
    private final Clock newsClock;

    public synchronized void publishLatestNews() {
        for (Parser parser : parsers) {
            try {
                publishNews(parser);
            } catch (Exception e) {
                log.warn("Failed to fetch news from {}", parser.getName(), e);
            }
        }
    }

    private void publishNews(Parser parser) {
        List<NewsData> news = parser.parseData();
        try {
            for (NewsData item : news) {
                if (!item.isEmpty()) {
                    List<File> images = item.getImages() == null
                            ? List.of()
                            : item.getImages().values().stream().limit(MAX_IMAGE_COUNT).toList();
                    try {
                        sendNewsMessages(formatMessage(parser, item), images);
                    } catch (Exception e) {
                        log.warn("Failed to send news from {}", parser.getName(), e);
                        break;
                    }
                }
            }
        } finally {
            for (NewsData item : news) {
                if (item.getImages() != null) {
                    deleteTemporaryFiles(new ArrayList<>(item.getImages().values()));
                }
            }
        }
    }

    private void sendNewsMessages(String message, List<File> imageFiles) {
        List<String> messageParts = splitMessage(message);
        if (messageParts.isEmpty()) {
            return;
        }
        if (imageFiles.isEmpty()) {
            messageSender.sendMessage(messageParts.getFirst());
        } else {
            try {
                messageSender.sendMessage(messageParts.getFirst(), imageMap(imageFiles));
            } catch (RuntimeException e) {
                log.warn("Failed to send Cherinfo news images, sending text without attachments: {}", e.getMessage());
                messageSender.sendMessage(messageParts.getFirst());
            }
        }
        for (int i = 1; i < messageParts.size(); i++) {
            messageSender.sendMessage(messageParts.get(i));
        }
    }

    private static Map<String, File> imageMap(List<File> imageFiles) {
        Map<String, File> images = new LinkedHashMap<>();
        for (int i = 0; i < imageFiles.size(); i++) {
            images.put("image-" + i, imageFiles.get(i));
        }
        return images;
    }

    private static List<String> splitMessage(String message) {
        List<String> parts = new ArrayList<>();
        String remaining = message.strip();
        while (!remaining.isEmpty()) {
            if (remaining.length() <= MAX_MESSAGE_LENGTH) {
                parts.add(remaining);
                break;
            }
            int splitIndex = splitIndex(remaining);
            parts.add(remaining.substring(0, splitIndex).strip());
            remaining = remaining.substring(splitIndex).strip();
        }
        return parts;
    }

    private static int splitIndex(String message) {
        int paragraphBreak = message.lastIndexOf("\n\n", MAX_MESSAGE_LENGTH);
        if (paragraphBreak > 0) {
            return paragraphBreak;
        }
        int lineBreak = message.lastIndexOf('\n', MAX_MESSAGE_LENGTH);
        if (lineBreak > 0) {
            return lineBreak;
        }
        int space = message.lastIndexOf(' ', MAX_MESSAGE_LENGTH);
        return space > 0 ? space : MAX_MESSAGE_LENGTH;
    }

    private static void deleteTemporaryFiles(List<File> imageFiles) {
        for (File imageFile : imageFiles) {
            try {
                Files.deleteIfExists(imageFile.toPath());
            } catch (IOException e) {
                log.warn("Failed to delete Cherinfo temporary image {}", imageFile.getAbsolutePath(), e);
            }
        }
    }

    private String formatMessage(Parser parser, NewsData news) {
        String publishedAt = news.getDate() == null
                ? "Дата не указана"
                : DATE_FORMAT.withZone(newsClock.getZone()).format(news.getDate());
        return "Новость %s%n%n%s%n%s%n%n%s".formatted(
                parser.getName(), news.getTitle(), publishedAt, news.getDescription());
    }
}
