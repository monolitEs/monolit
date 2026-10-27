package org.monolites.monolit.configs;

import org.monolites.monolit.clients.CliToChatGptClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "monolit.chat.enabled", havingValue = "true")
public class ChatClientConfig {

    @Bean(destroyMethod = "shutdownNow")
    public HttpClient chatHttpClient(@Value("${monolit.chat.connect-timeout:PT5S}") Duration connectTimeout) {
        return HttpClient.newBuilder().connectTimeout(connectTimeout).build();
    }

    @Bean
    public CliToChatGptClient cliToChatGptClient(RestClient.Builder builder,
                                                 HttpClient chatHttpClient, @Value("${monolit.chat.base-url}") String baseUrl,
                                                 @Value("${monolit.chat.request-timeout:PT11M}") Duration requestTimeout) {
        var requestFactory = new JdkClientHttpRequestFactory(chatHttpClient);
        requestFactory.setReadTimeout(requestTimeout);
        var restClient = builder.baseUrl(baseUrl).requestFactory(requestFactory).build();
        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(restClient))
                .build().createClient(CliToChatGptClient.class);
    }
}
