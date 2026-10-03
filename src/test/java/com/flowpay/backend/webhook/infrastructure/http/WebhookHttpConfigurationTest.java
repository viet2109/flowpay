package com.flowpay.backend.webhook.infrastructure.http;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import java.net.http.HttpClient;
import java.time.Duration;
import static org.assertj.core.api.Assertions.*;

class WebhookHttpConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(WebhookHttpConfiguration.class);

    @Test
    void createsDedicatedClientWithConfiguredTimeoutAndNoRedirects() {
        context.withPropertyValues("flowpay.webhook.http.connect-timeout=2s", "flowpay.webhook.http.request-timeout=5s")
                .run(result -> {
                    assertThat(result).hasNotFailed();
                    HttpClient client = result.getBean(WebhookHttpConfiguration.HTTP_CLIENT, HttpClient.class);
                    assertThat(client.followRedirects()).isEqualTo(HttpClient.Redirect.NEVER);
                    assertThat(client.connectTimeout()).contains(Duration.ofSeconds(2));
                    assertThat(result.getBean(WebhookHttpProperties.class).requestTimeout()).isEqualTo(Duration.ofSeconds(5));
                });
    }

    @Test
    void missingZeroNegativeOrMalformedTimeoutFailsStartup() {
        context.run(result -> assertThat(result).hasFailed());
        for (String value : new String[]{"0s", "-1s", "invalid"}) {
            context.withPropertyValues("flowpay.webhook.http.connect-timeout=" + value,
                    "flowpay.webhook.http.request-timeout=5s").run(result -> assertThat(result).hasFailed());
            context.withPropertyValues("flowpay.webhook.http.connect-timeout=2s",
                    "flowpay.webhook.http.request-timeout=" + value).run(result -> assertThat(result).hasFailed());
        }
    }
}
