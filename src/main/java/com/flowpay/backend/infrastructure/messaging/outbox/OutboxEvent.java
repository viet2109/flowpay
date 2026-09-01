package com.flowpay.backend.infrastructure.messaging.outbox;

import java.time.Instant;
import java.util.Objects;

public record OutboxEvent(
        Long internalId,
        String eventId,
        String aggregateType,
        String aggregateId,
        String eventType,
        String payload,
        OutboxStatus status,
        Instant occurredAt,
        Instant availableAt,
        Instant publishedAt,
        int retryCount,
        String lastError,
        Instant createdAt
) {

    private static final String EVENT_ID_PREFIX = "ievt_";

    public OutboxEvent {
        if (internalId != null && internalId <= 0) {
            throw new IllegalArgumentException("internalId must be positive");
        }
        eventId = requireEventId(eventId);
        aggregateType = requireText(aggregateType, "aggregateType");
        aggregateId = requireText(aggregateId, "aggregateId");
        eventType = requireText(eventType, "eventType");
        payload = requirePayload(payload);
        status = Objects.requireNonNull(status, "status must not be null");
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        availableAt = Objects.requireNonNull(availableAt, "availableAt must not be null");
        createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        if (retryCount < 0) {
            throw new IllegalArgumentException("retryCount must not be negative");
        }
        lastError = optionalText(lastError, "lastError");
        validateLifecycle(status, publishedAt, retryCount, lastError);
    }

    public static OutboxEvent pending(
            String eventId,
            String aggregateType,
            String aggregateId,
            String eventType,
            String payload,
            Instant occurredAt,
            Instant createdAt
    ) {
        Instant materializedAt = Objects.requireNonNull(
                createdAt,
                "createdAt must not be null"
        );
        return new OutboxEvent(
                null,
                eventId,
                aggregateType,
                aggregateId,
                eventType,
                payload,
                OutboxStatus.PENDING,
                occurredAt,
                materializedAt,
                null,
                0,
                null,
                materializedAt
        );
    }

    private static void validateLifecycle(
            OutboxStatus status,
            Instant publishedAt,
            int retryCount,
            String lastError
    ) {
        switch (status) {
            case PENDING -> {
                if (publishedAt != null || retryCount != 0 || lastError != null) {
                    throw new IllegalArgumentException(
                            "a PENDING Outbox event must have its initial publication state"
                    );
                }
            }
            case PUBLISHED -> {
                if (publishedAt == null) {
                    throw new IllegalArgumentException(
                            "a PUBLISHED Outbox event must have publishedAt"
                    );
                }
                if (lastError != null) {
                    throw new IllegalArgumentException(
                            "a PUBLISHED Outbox event must not have lastError"
                    );
                }
            }
            case FAILED -> {
                if (publishedAt != null) {
                    throw new IllegalArgumentException(
                            "a FAILED Outbox event must not have publishedAt"
                    );
                }
                if (retryCount == 0 || lastError == null) {
                    throw new IllegalArgumentException(
                            "a FAILED Outbox event must have retry metadata"
                    );
                }
            }
        }
    }

    private static String requireEventId(String value) {
        String eventId = requireText(value, "eventId");
        if (!eventId.startsWith(EVENT_ID_PREFIX)
                || eventId.length() == EVENT_ID_PREFIX.length()) {
            throw new IllegalArgumentException(
                    "eventId must start with ievt_ and contain an identifier"
            );
        }
        return eventId;
    }

    private static String requirePayload(String value) {
        Objects.requireNonNull(value, "payload must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("payload must not be blank");
        }
        return value;
    }

    private static String requireText(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }

    private static String optionalText(String value, String fieldName) {
        return value == null ? null : requireText(value, fieldName);
    }
}
