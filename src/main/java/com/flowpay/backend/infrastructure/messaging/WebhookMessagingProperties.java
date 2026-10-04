package com.flowpay.backend.infrastructure.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.Objects;

/** Additive Webhook configuration; existing Ledger property names remain unchanged. */
@ConfigurationProperties(prefix = "flowpay.messaging")
public record WebhookMessagingProperties(Topology topology, Consumer webhookConsumer) {
    public WebhookMessagingProperties {
        Objects.requireNonNull(topology, "topology must not be null");
        Objects.requireNonNull(webhookConsumer, "webhookConsumer must not be null");
    }

    public record Topology(String webhookQueue, String webhookDeadLetterQueue, String webhookDeadLetterRoutingKey) {
        public Topology {
            webhookQueue = text(webhookQueue);
            webhookDeadLetterQueue = text(webhookDeadLetterQueue);
            webhookDeadLetterRoutingKey = text(webhookDeadLetterRoutingKey);
            if (webhookQueue.equals(webhookDeadLetterQueue)) {
                throw new IllegalArgumentException("Webhook queues must use distinct names");
            }
        }
        private static String text(String value) {
            String normalized = Objects.requireNonNull(value, "Webhook topology name must not be null").trim();
            if (normalized.isEmpty()) {
                throw new IllegalArgumentException("Webhook topology name must not be blank");
            }
            return normalized;
        }
    }

    public record Consumer(boolean enabled, FlowPayMessagingProperties.Retry retry) {
        public Consumer {
            Objects.requireNonNull(retry, "Webhook consumer retry must not be null");
        }
    }
}
