package com.flowpay.backend.infrastructure.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Objects;

@ConfigurationProperties(prefix = "flowpay.messaging")
public record FlowPayMessagingProperties(
        Topology topology,
        LedgerConsumer ledgerConsumer,
        Outbox outbox
) {

    public FlowPayMessagingProperties {
        topology = Objects.requireNonNull(topology, "topology must not be null");
        ledgerConsumer = Objects.requireNonNull(
                ledgerConsumer,
                "ledgerConsumer must not be null"
        );
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

    public record LedgerConsumer(
            boolean enabled,
            Retry retry
    ) {

        public LedgerConsumer {
            retry = Objects.requireNonNull(retry, "ledgerConsumer.retry must not be null");
        }
    }

    public record Retry(
            int maxAttempts,
            Duration initialInterval,
            double multiplier,
            Duration maxInterval
    ) {

        public Retry {
            if (maxAttempts <= 0) {
                throw new IllegalArgumentException(
                        "ledgerConsumer.retry.maxAttempts must be positive"
                );
            }
            initialInterval = requirePositive(
                    initialInterval,
                    "ledgerConsumer.retry.initialInterval"
            );
            if (!Double.isFinite(multiplier) || multiplier < 1.0) {
                throw new IllegalArgumentException(
                        "ledgerConsumer.retry.multiplier must be finite and at least 1"
                );
            }
            maxInterval = requirePositive(
                    maxInterval,
                    "ledgerConsumer.retry.maxInterval"
            );
            if (maxInterval.compareTo(initialInterval) < 0) {
                throw new IllegalArgumentException(
                        "ledgerConsumer.retry.maxInterval must not be less than initialInterval"
                );
            }
        }
    }

    public record Outbox(
            Duration publisherConfirmTimeout,
            Relay relay
    ) {

        public Outbox {
            publisherConfirmTimeout = requirePositive(
                    publisherConfirmTimeout,
                    "outbox.publisherConfirmTimeout"
            );
            relay = Objects.requireNonNull(relay, "outbox.relay must not be null");
        }
    }

    public record Relay(
            boolean enabled,
            Duration fixedDelay,
            int batchSize,
            Duration initialBackoff,
            Duration maxBackoff
    ) {

        public Relay {
            fixedDelay = requirePositive(fixedDelay, "outbox.relay.fixedDelay");
            if (batchSize <= 0) {
                throw new IllegalArgumentException(
                        "outbox.relay.batchSize must be positive"
                );
            }
            initialBackoff = requirePositive(
                    initialBackoff,
                    "outbox.relay.initialBackoff"
            );
            maxBackoff = requirePositive(maxBackoff, "outbox.relay.maxBackoff");
            if (maxBackoff.compareTo(initialBackoff) < 0) {
                throw new IllegalArgumentException(
                        "outbox.relay.maxBackoff must not be less than initialBackoff"
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

    private static Duration requirePositive(Duration value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " must not be null");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
        return value;
    }
}
