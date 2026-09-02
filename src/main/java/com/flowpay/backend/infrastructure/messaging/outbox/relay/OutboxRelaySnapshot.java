package com.flowpay.backend.infrastructure.messaging.outbox.relay;

import com.flowpay.backend.infrastructure.messaging.outbox.OutboxEvent;

import java.time.Instant;
import java.util.Objects;

public record OutboxRelaySnapshot(
        String eventId,
        String aggregateType,
        String aggregateId,
        String eventType,
        String payload,
        Instant occurredAt,
        int retryCount
) {

    public OutboxRelaySnapshot {
        eventId = requireText(eventId, "eventId");
        aggregateType = requireText(aggregateType, "aggregateType");
        aggregateId = requireText(aggregateId, "aggregateId");
        eventType = requireText(eventType, "eventType");
        payload = requireText(payload, "payload");
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        if (retryCount < 0) {
            throw new IllegalArgumentException("retryCount must not be negative");
        }
    }

    static OutboxRelaySnapshot from(OutboxEvent event) {
        OutboxEvent source = Objects.requireNonNull(event, "event must not be null");
        return new OutboxRelaySnapshot(
                source.eventId(),
                source.aggregateType(),
                source.aggregateId(),
                source.eventType(),
                source.payload(),
                source.occurredAt(),
                source.retryCount()
        );
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
