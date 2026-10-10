package org.monolites.monolit.schedulers;

import lombok.RequiredArgsConstructor;
import org.monolites.monolit.services.NewsService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "monolit.news.cherinfo.enabled", havingValue = "true")
public class NewsScheduler {

    private final NewsService newsService;

    @EventListener(ApplicationReadyEvent.class)
    public void publishNewsOnStartup() {
        newsService.publishLatestNews();
    }

    @Scheduled(cron = "${monolit.news.cherinfo.cron}", zone = "${monolit.news.zone}")
    public void publishNewsHourly() {
        newsService.publishLatestNews();
    }
}
