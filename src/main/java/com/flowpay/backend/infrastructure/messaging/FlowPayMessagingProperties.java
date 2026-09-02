package com.flowpay.backend.infrastructure.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Objects;

@ConfigurationProperties(prefix = "flowpay.messaging")
public record FlowPayMessagingProperties(
        Topology topology,
        Outbox outbox
) {

    public FlowPayMessagingProperties {
        topology = Objects.requireNonNull(topology, "topology must not be null");
        outbox = Objects.requireNonNull(outbox, "outbox must not be null");
    }

    public record Topology(
            String exchange,
            String ledgerQueue,
            String deadLetterExchange,
            String ledgerDeadLetterQueue,
            String deadLetterRoutingKey
    ) {

        public Topology {
            exchange = requireText(exchange, "topology.exchange");
            ledgerQueue = requireText(ledgerQueue, "topology.ledgerQueue");
            deadLetterExchange = requireText(
                    deadLetterExchange,
                    "topology.deadLetterExchange"
            );
            ledgerDeadLetterQueue = requireText(
                    ledgerDeadLetterQueue,
                    "topology.ledgerDeadLetterQueue"
            );
            deadLetterRoutingKey = requireText(
                    deadLetterRoutingKey,
                    "topology.deadLetterRoutingKey"
            );
            if (exchange.equals(deadLetterExchange)) {
                throw new IllegalArgumentException(
                        "topology exchanges must use distinct names"
                );
            }
            if (ledgerQueue.equals(ledgerDeadLetterQueue)) {
                throw new IllegalArgumentException(
                        "topology queues must use distinct names"
                );
            }
        }
    }

    public record Outbox(Duration publisherConfirmTimeout) {

        public Outbox {
            Objects.requireNonNull(
                    publisherConfirmTimeout,
                    "outbox.publisherConfirmTimeout must not be null"
            );
            if (publisherConfirmTimeout.isZero() || publisherConfirmTimeout.isNegative()) {
                throw new IllegalArgumentException(
                        "outbox.publisherConfirmTimeout must be positive"
                );
            }
        }
    }

    private static String requireText(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }
}
