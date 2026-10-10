package org.monolites.monolit.schedulers;

import org.junit.jupiter.api.Test;
import org.monolites.monolit.services.NewsService;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class NewsSchedulerTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(SchedulerConfiguration.class)
            .withBean(NewsService.class, () -> mock(NewsService.class))
            .withPropertyValues(
                    "monolit.news.cherinfo.cron=0 0 0 1 1 *",
                    "monolit.news.zone=Europe/Moscow"
            );

    @Test
    void disablesStartupAndScheduledPublicationWhenFlagIsMissing() {
        assertPublicationDisabled(contextRunner);
    }

    @Test
    void disablesStartupAndScheduledPublicationWhenFlagIsFalse() {
        assertPublicationDisabled(contextRunner.withPropertyValues("monolit.news.cherinfo.enabled=false"));
    }

    @Test
    void enablesStartupAndScheduledPublicationWhenExplicitlyRequested() {
        contextRunner.withPropertyValues("monolit.news.cherinfo.enabled=true").run(context -> {
            assertThat(context).hasSingleBean(NewsScheduler.class);
            assertThat(context.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks()).hasSize(1);
            context.publishEvent(new ApplicationReadyEvent(
                    new SpringApplication(SchedulerConfiguration.class), new String[0],
                    context.getSourceApplicationContext(), Duration.ZERO
            ));
            verify(context.getBean(NewsService.class)).publishLatestNews();
        });
    }

    @Test
    void delegatesStartupAndHourlyPublication() {
        NewsService service = mock(NewsService.class);
        NewsScheduler scheduler = new NewsScheduler(service);

        scheduler.publishNewsOnStartup();
        scheduler.publishNewsHourly();

        verify(service, times(2)).publishLatestNews();
    }

    private void assertPublicationDisabled(ApplicationContextRunner runner) {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(NewsScheduler.class);
            assertThat(context.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks()).isEmpty();
            context.publishEvent(new ApplicationReadyEvent(
                    new SpringApplication(SchedulerConfiguration.class), new String[0],
                    context.getSourceApplicationContext(), Duration.ZERO
            ));
            verifyNoInteractions(context.getBean(NewsService.class));
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @Import(NewsScheduler.class)
    static class SchedulerConfiguration {
    }
}
