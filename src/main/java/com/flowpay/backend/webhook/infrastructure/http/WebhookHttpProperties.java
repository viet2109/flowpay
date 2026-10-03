package com.flowpay.backend.webhook.infrastructure.http;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;
import java.util.Objects;

@ConfigurationProperties(prefix = "flowpay.webhook.http")
public record WebhookHttpProperties(Duration connectTimeout, Duration requestTimeout) {
    public WebhookHttpProperties {
        positive(connectTimeout);
        positive(requestTimeout);
    }

    private static void positive(Duration duration) {
        Objects.requireNonNull(duration, "Webhook HTTP timeouts must be configured");
        if (duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException("Webhook HTTP timeouts must be positive");
        }
    }
}
