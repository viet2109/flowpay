package com.flowpay.backend.webhook.infrastructure.http;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.net.http.HttpClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WebhookHttpProperties.class)
class WebhookHttpConfiguration {
    static final String HTTP_CLIENT = "webhookHttpClient";

    @Bean(name = HTTP_CLIENT, destroyMethod = "close")
    HttpClient webhookHttpClient(WebhookHttpProperties properties) {
        return HttpClient.newBuilder().connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }
}
