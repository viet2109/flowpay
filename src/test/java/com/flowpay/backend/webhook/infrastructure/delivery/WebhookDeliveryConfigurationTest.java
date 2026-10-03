package com.flowpay.backend.webhook.infrastructure.delivery;

import com.flowpay.backend.webhook.application.WebhookDeliveryWorker;
import com.flowpay.backend.webhook.infrastructure.http.WebhookHttpProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WebhookDeliveryConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(WebhookDeliveryConfiguration.class, HttpPropertiesConfiguration.class, WebhookDeliveryScheduler.class)
            .withBean(WebhookDeliveryWorker.class, () -> mock(WebhookDeliveryWorker.class))
            .withPropertyValues("flowpay.webhook.delivery-worker.enabled=true", "flowpay.webhook.delivery-worker.fixed-delay=1s",
                    "flowpay.webhook.delivery-worker.batch-size=100", "flowpay.webhook.delivery-worker.lease-timeout=30s",
                    "flowpay.webhook.delivery.retry-delays=10s,30s,2m,10m,1h", "flowpay.webhook.delivery.retry-jitter-max=0.20",
                    "flowpay.webhook.http.connect-timeout=2s", "flowpay.webhook.http.request-timeout=5s");

    @Test
    void schedulerExistsOnlyWhenEnabledAndDelegatesOneBatch() {
        context.run(result -> {
            assertThat(result).hasNotFailed().hasSingleBean(WebhookDeliveryScheduler.class);
            result.getBean(WebhookDeliveryScheduler.class).deliver();
            verify(result.getBean(WebhookDeliveryWorker.class)).deliverBatch();
        });
        context.withPropertyValues("flowpay.webhook.delivery-worker.enabled=false")
                .run(result -> assertThat(result).hasNotFailed().doesNotHaveBean(WebhookDeliveryScheduler.class));
    }

    @Test
    void rejectsInvalidDurationsBatchLeaseAndRetryConfiguration() {
        for (String invalid : new String[]{"fixed-delay=0s", "fixed-delay=-1s", "fixed-delay=invalid",
                "batch-size=0", "batch-size=-1", "lease-timeout=0s", "lease-timeout=5s", "lease-timeout=4s"}) {
            context.withPropertyValues("flowpay.webhook.delivery-worker." + invalid)
                    .run(result -> assertThat(result).hasFailed());
        }
        for (String invalid : new String[]{"retry-delays=10s", "retry-delays=0s,30s,2m,10m,1h",
                "retry-jitter-max=-0.1", "retry-jitter-max=1.1"}) {
            context.withPropertyValues("flowpay.webhook.delivery." + invalid).run(result -> assertThat(result).hasFailed());
        }
    }

    @Test
    void schedulerDoesNotPropagateBatchFailure() {
        var worker = mock(WebhookDeliveryWorker.class);
        doThrow(new IllegalStateException("sensitive")).when(worker).deliverBatch();
        assertThatCode(() -> new WebhookDeliveryScheduler(worker).deliver()).doesNotThrowAnyException();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(WebhookHttpProperties.class)
    static class HttpPropertiesConfiguration { }
}
