package org.monolites.monolit.parser.utils;

import org.monolites.monolit.models.dtos.ImageDto;
import org.monolites.monolit.models.exception.DownloadExcpetion;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Connection;
import org.jsoup.Jsoup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;
import java.util.UUID;

@Slf4j
public class ImageDownloader {

    private ImageDownloader() {
        throw new IllegalStateException("Utility class");
    }

    public static ImageDto downloadImage(String link) {
        try {

            Connection.Response resultImageResponse = Jsoup.connect(link)
                    .ignoreContentType(true).execute();

            String extension = determineExtension(resultImageResponse.contentType());
            ImageDto imageDto = new ImageDto();
            imageDto.setName(UUID.randomUUID() + extension);
            imageDto.setPath(Paths.get("images", imageDto.getName()));

            Files.createDirectories(imageDto.getPath().getParent());
            Files.write(imageDto.getPath(), resultImageResponse.bodyAsBytes());

            return imageDto;
        } catch (IOException e) {
            log.error(e.getMessage());
            throw new DownloadExcpetion("Ошибка загрузки", e);
        }
    }

    private static String determineExtension(String contentType) {
        if (contentType == null) return ".jpg";

        Map<String, String> mimeMap = Map.of(
                "image/jpeg", ".jpg",
                "image/png", ".png",
                "image/gif", ".gif",
                "image/webp", ".webp",
                "image/bmp", ".bmp",
                "image/svg+xml", ".svg"
        );

        return mimeMap.entrySet().stream()
                .filter(entry -> contentType.contains(entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(".jpg");
    }
}
