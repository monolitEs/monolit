package org.monolites.monolit.handlers;

import com.vk.api.sdk.client.VkApiClient;
import com.vk.api.sdk.client.actors.GroupActor;
import com.vk.api.sdk.objects.callback.MessageNew;
import com.vk.api.sdk.objects.callback.MessageObject;
import com.vk.api.sdk.objects.messages.Keyboard;
import com.vk.api.sdk.objects.messages.KeyboardButtonActionText;
import com.vk.api.sdk.objects.messages.Message;
import com.vk.api.sdk.queries.messages.MessagesSendQueryWithDeprecated;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.monolites.monolit.MonolitApplication;
import org.monolites.monolit.configs.JacksonConfig;
import org.monolites.monolit.handlers.callbacks.CallbackPayloadDispatcher;
import org.monolites.monolit.handlers.callbacks.NewsCallbackPayloadHandler;
import org.monolites.monolit.handlers.text.TextMessageDispatcher;
import org.monolites.monolit.init.VkKeyboardInitializer;
import org.monolites.monolit.models.dtos.NewsData;
import org.monolites.monolit.parser.Parser;
import org.monolites.monolit.repositories.NewsParserStateRepository;
import org.monolites.monolit.services.DialogueModeService;
import org.monolites.monolit.services.NewsService;
import org.monolites.monolit.services.VkMessageSenderService;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class NewsButtonFlowTest {

    @Test
    void startupKeyboardToButtonToPublisherSendsNewsOnlyOnOwnerClick() throws Exception {
        VkApiClient vk = mock(VkApiClient.class, RETURNS_DEEP_STUBS);
        GroupActor actor = mock(GroupActor.class);
        MessagesSendQueryWithDeprecated query = mock(MessagesSendQueryWithDeprecated.class, RETURNS_SELF);
        when(vk.messages().sendDeprecated(actor)).thenReturn(query);
        var mapper = new JacksonConfig().objectMapper();
        VkMessageSenderService sender = new VkMessageSenderService(vk, actor, mapper, "42");
        Parser parser = mock(Parser.class);
        NewsData article = new NewsData();
        article.setTitle("Fresh article");
        article.setDescription("Full text.");
        when(parser.getName()).thenReturn("Fixture");
        when(parser.parseData()).thenReturn(List.of(article), List.of());
        NewsService service = new NewsService(List.of(parser), sender, Clock.systemUTC());
        var callback = new NewsCallbackPayloadHandler(service);
        var dispatcher = new CallbackPayloadDispatcher(mapper, List.of(callback));
        var textDispatcher = new TextMessageDispatcher(new DialogueModeService(), List.of());
        var handler = new GroupLongPoolApiHandler(vk, actor, dispatcher, textDispatcher, "42");

        new VkKeyboardInitializer(sender).run();

        verify(parser, never()).parseData();
        ArgumentCaptor<Keyboard> keyboards = ArgumentCaptor.forClass(Keyboard.class);
        verify(query).keyboard(keyboards.capture());
        Keyboard keyboard = keyboards.getValue();
        assertThat(keyboard.getInline()).isFalse();
        assertThat(keyboard.getOneTime()).isFalse();
        assertThat(keyboard.getButtons()).singleElement().satisfies(row -> assertThat(row).hasSize(1));
        var action = (KeyboardButtonActionText) keyboard.getButtons().getFirst().getFirst().getAction();
        assertThat(action.getLabel()).isEqualTo("Получить новости");
        String payload = action.getPayload();
        handler.messageNew(1, event(42L, "Меню", null));
        handler.messageNew(1, event(42L, "Начать", null));
        handler.messageNew(1, event(42L, action.getLabel(), null));
        handler.messageNew(1, event(42L, null, null));
        handler.messageNew(1, event(99L, action.getLabel(), payload));
        handler.messageNew(1, event(42L, "Text", "{\"type\":\"news\",\"version\":1,\"data\":\"unknown\"}"));
        handler.messageNew(1, event(42L, "Text", "{\"type\":\"news\",\"version\":1,\"data\":null}"));
        handler.messageNew(1, event(42L, "Text", "{\"type\":\"news\",\"version\":2,\"data\":\"latest\"}"));
        verify(parser, never()).parseData();

        handler.messageNew(1, event(42L, action.getLabel(), payload));
        handler.messageNew(1, event(42L, action.getLabel(), payload));

        verify(parser, times(2)).parseData();
        verify(query, times(2)).userId(42L);
        ArgumentCaptor<String> messages = ArgumentCaptor.forClass(String.class);
        verify(query, times(2)).message(messages.capture());
        assertThat(messages.getAllValues()).satisfiesExactly(
                startup -> assertThat(startup).isEqualTo("Бот готов."),
                news -> assertThat(news).contains("Fresh article", "Full text."));
        verify(query, times(2)).execute();
    }

    @Test
    void applicationDoesNotFetchNewsOnReadyOrRegisterScheduledTasksEvenWithOldFlag() {
        NewsParserStateRepository repository = mock(NewsParserStateRepository.class);
        when(repository.findById(anyString())).thenThrow(new IllegalStateException("Unexpected automatic request"));
        new ApplicationContextRunner()
                .withUserConfiguration(MonolitApplication.class)
                .withBean(NewsParserStateRepository.class, () -> repository)
                .withPropertyValues(
                        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                                + "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,"
                                + "org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration",
                        "monolit.vk.long-poll.enabled=false", "monolit.news.cherinfo.enabled=true",
                        "monolit.news.cherinfo.rss-url=http://127.0.0.1:1/rss",
                        "monolit.news.zone=Europe/Moscow", "VK_MY_ID=42", "VK_GROUP_ID=1", "VK_GROUP_TOKEN=fixture")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(NewsCallbackPayloadHandler.class);
                    assertThat(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class)).isEmpty();
                    context.publishEvent(new ApplicationReadyEvent(
                            new SpringApplication(MonolitApplication.class), new String[0],
                            context.getSourceApplicationContext(), Duration.ZERO));
                    verifyNoInteractions(repository);
                });
    }

    private static MessageNew event(long fromId, String text, String payload) {
        return new MessageNew().setObject(new MessageObject().setMessage(
                new Message().setFromId(fromId).setText(text).setPayload(payload)));
    }
}
