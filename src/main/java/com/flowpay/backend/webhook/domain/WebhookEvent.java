package com.flowpay.backend.webhook.domain;

import lombok.Getter;
import lombok.experimental.Accessors;

import java.time.Instant;
import java.util.Objects;

/** Immutable Webhook-owned snapshot; never reconstructed from current source state. */
@Getter
@Accessors(fluent = true)
public final class WebhookEvent {
    private final Long internalId;
    private final String publicId;
    private final String sourceEventId;
    private final long merchantId;
    private final WebhookEventType eventType;
    private final WebhookResourceType resourceType;
    private final String resourceId;
    private final String payload;
    private final Instant occurredAt;
    private final Instant createdAt;

    private WebhookEvent(Long internalId, String publicId, String sourceEventId, long merchantId,
                         WebhookEventType eventType, WebhookResourceType resourceType,
                         String resourceId, String payload, Instant occurredAt, Instant createdAt) {
        if (internalId != null && internalId <= 0) {
            throw new IllegalArgumentException("internalId must be positive");
        }
        if (merchantId <= 0) {
            throw new IllegalArgumentException("merchantId must be positive");
        }
        this.internalId = internalId;
        this.publicId = publicId(publicId, "evt_");
        this.sourceEventId = requireText(sourceEventId, "sourceEventId");
        this.merchantId = merchantId;
        this.eventType = Objects.requireNonNull(eventType, "eventType must not be null");
        this.resourceType = Objects.requireNonNull(resourceType, "resourceType must not be null");
        if (resourceType != WebhookResourceType.forEventType(eventType)) {
            throw new IllegalArgumentException("eventType and resourceType must agree");
        }
        this.resourceId = publicId(resourceId, resourceType.publicIdPrefix());
        Objects.requireNonNull(payload, "payload must not be null");
        if (payload.isBlank()) {
            throw new IllegalArgumentException("payload must not be blank");
        }
        // Preserve supplied JSON, not a mutable JSON node or a domain entity.
        // Serialization/validation belongs to the materializer; PostgreSQL enforces JSONB.
        this.payload = payload;
        this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    public static WebhookEvent create(String publicId, String sourceEventId, long merchantId,
                                      WebhookEventType eventType, WebhookResourceType resourceType,
                                      String resourceId, String payload, Instant occurredAt, Instant createdAt) {
        return new WebhookEvent(null, publicId, sourceEventId, merchantId, eventType, resourceType,
                resourceId, payload, occurredAt, createdAt);
    }

    public static WebhookEvent rehydrate(long internalId, String publicId, String sourceEventId,
                                         long merchantId, WebhookEventType eventType, WebhookResourceType resourceType,
                                         String resourceId, String payload, Instant occurredAt, Instant createdAt) {
        return new WebhookEvent(internalId, publicId, sourceEventId, merchantId, eventType, resourceType,
                resourceId, payload, occurredAt, createdAt);
    }

    private static String publicId(String id, String prefix) {
        String value = requireText(id, "public identity");
        if (!value.startsWith(prefix) || value.length() <= prefix.length() || value.length() > 64) {
            throw new IllegalArgumentException("public identity must use its expected prefix and fit 64 characters");
        }
        return value;
    }

    private static String requireText(String text, String field) {
        String value = Objects.requireNonNull(text, field + " must not be null").trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
