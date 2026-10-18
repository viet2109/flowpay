package com.flowpay.backend.webhook.infrastructure.http;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.apache.hc.client5.http.impl.async.CloseableHttpAsyncClient;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.reactor.IOReactorStatus;
import java.time.Duration;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

class WebhookHttpConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(WebhookHttpConfiguration.class);

    @Test
    void createsDedicatedClientWithConfiguredTimeoutAndNoRedirects() {
        context.withPropertyValues("flowpay.webhook.http.connect-timeout=2s", "flowpay.webhook.http.request-timeout=5s")
                .run(result -> {
                    assertThat(result).hasNotFailed();
                    var client = result.getBean(WebhookHttpConfiguration.HTTP_CLIENT, CloseableHttpAsyncClient.class);
                    // start() schedules the reactor; its thread need not have run before bean publication.
                    await().atMost(Duration.ofSeconds(2))
                            .untilAsserted(() -> assertThat(client.getStatus()).isEqualTo(IOReactorStatus.ACTIVE));
                    assertThat(result.getBean(WebhookHttpProperties.class).connectTimeout()).isEqualTo(Duration.ofSeconds(2));
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

    @Test
    void productionRequiresProxyAndConfiguredSelectorNeverFallsBackToDirect() {
        var configured = context.withPropertyValues("flowpay.webhook.http.connect-timeout=2s",
                "flowpay.webhook.http.request-timeout=5s").withInitializer(application ->
                        application.getEnvironment().setActiveProfiles("prod"));
        configured.run(result -> assertThat(result).hasFailed());
        configured.withPropertyValues("flowpay.webhook.egress.proxy-host=127.0.0.1",
                "flowpay.webhook.egress.proxy-port=3128").run(result -> {
            assertThat(result).hasNotFailed();
            assertThatCode(() -> {
                var planner = result.getBean(WebhookEgressProperties.class).routePlanner();
                var target = new HttpHost("https", "merchant.example", 443);
                for (int retry = 0; retry < 2; retry++) {
                    var route = planner.determineRoute(target, HttpClientContext.create());
                    assertThat(route.getProxyHost()).isEqualTo(new HttpHost("http", "127.0.0.1", 3128));
                    assertThat(route.isTunnelled()).isTrue();
                }
            }).doesNotThrowAnyException();
        });
    }

    @Test
    void developmentExplicitlyDisablesImplicitJvmProxyAndRejectsPartialProxyConfiguration() {
        var configured = context.withPropertyValues("flowpay.webhook.http.connect-timeout=2s",
                "flowpay.webhook.http.request-timeout=5s");
        configured.withSystemProperties("http.proxyHost=untrusted.invalid", "https.proxyHost=untrusted.invalid")
                .run(result -> assertThatCode(() -> assertThat(result.getBean(WebhookEgressProperties.class)
                        .routePlanner().determineRoute(new HttpHost("http", "localhost", 80), HttpClientContext.create())
                        .getProxyHost()).isNull()).doesNotThrowAnyException());
        configured.withPropertyValues("flowpay.webhook.egress.proxy-host=egress")
                .run(result -> assertThat(result).hasFailed());
        configured.withPropertyValues("flowpay.webhook.egress.proxy-port=3128")
                .run(result -> assertThat(result).hasFailed());
        for (String host : new String[]{"https://proxy", "user:password@proxy", "proxy:3128", "proxy/path", "proxy?query", "proxy#fragment"}) {
            configured.withPropertyValues("flowpay.webhook.egress.proxy-host=" + host,
                    "flowpay.webhook.egress.proxy-port=3128").run(result -> assertThat(result).hasFailed());
        }
    }

    @Test
    void productionApacheTlsDoesNotUseTheOldJdkHostnameVerificationDisableFlag() {
        String key = "jdk.internal.httpclient.disableHostnameVerification";
        String original = System.getProperty(key);
        try {
            for (String unsafe : new String[]{"true", ""}) {
                System.setProperty(key, unsafe);
                context.withPropertyValues("flowpay.webhook.http.connect-timeout=2s",
                        "flowpay.webhook.http.request-timeout=5s", "flowpay.webhook.egress.proxy-host=127.0.0.1",
                        "flowpay.webhook.egress.proxy-port=3128").withInitializer(application ->
                        application.getEnvironment().setActiveProfiles("prod"))
                        .run(result -> assertThat(result).hasNotFailed());
            }
        } finally {
            if (original == null) System.clearProperty(key); else System.setProperty(key, original);
        }
    }
}
