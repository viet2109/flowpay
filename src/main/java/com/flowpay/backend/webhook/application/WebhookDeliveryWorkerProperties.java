package com.flowpay.backend.webhook.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

@ConfigurationProperties("flowpay.webhook.delivery-worker")
public record WebhookDeliveryWorkerProperties(boolean enabled, Duration fixedDelay, int batchSize, Duration leaseTimeout) {
    public WebhookDeliveryWorkerProperties {
        if (fixedDelay == null || fixedDelay.isNegative() || fixedDelay.isZero()
                || leaseTimeout == null || leaseTimeout.isNegative() || leaseTimeout.isZero() || batchSize <= 0) {
            throw new IllegalArgumentException("Webhook worker durations and batch size must be positive");
        }
    }
}
