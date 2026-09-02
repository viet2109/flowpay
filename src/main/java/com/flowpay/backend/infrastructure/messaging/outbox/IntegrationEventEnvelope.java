package com.flowpay.backend.infrastructure.messaging.outbox;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Objects;

public record IntegrationEventEnvelope(
        String eventId,
        String eventType,
        String aggregateType,
        String aggregateId,
        Instant occurredAt,
        JsonNode payload
) {

    public IntegrationEventEnvelope {
        eventId = requireText(eventId, "eventId");
        eventType = requireText(eventType, "eventType");
        aggregateType = requireText(aggregateType, "aggregateType");
        aggregateId = requireText(aggregateId, "aggregateId");
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        payload = Objects.requireNonNull(payload, "payload must not be null").deepCopy();
        if (!payload.isObject()) {
            throw new IllegalArgumentException("payload must be a JSON object");
        }
    }

    @Override
    public JsonNode payload() {
        return payload.deepCopy();
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
