package com.flowpay.backend.webhook.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.List;

@ConfigurationProperties("flowpay.webhook.delivery")
public record WebhookDeliveryRetryProperties(List<Duration> retryDelays, BigDecimal retryJitterMax) {
    public WebhookDeliveryRetryProperties {
        if (retryDelays == null || retryDelays.size() != 5 || retryDelays.stream()
                .anyMatch(d -> d == null || d.isNegative() || d.isZero())
                || retryJitterMax == null || retryJitterMax.signum() < 0 || retryJitterMax.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("Webhook retries require five positive delays and jitter in [0, 1]");
        }
        retryDelays = List.copyOf(retryDelays);
        // Fail at startup rather than overflow while finalizing a delivery.
        for (Duration delay : retryDelays) {
            BigDecimal.valueOf(delay.toNanos()).multiply(BigDecimal.ONE.add(retryJitterMax))
                    .setScale(0, RoundingMode.DOWN).longValueExact();
        }
    }
}
