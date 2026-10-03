package com.flowpay.backend.webhook.application;

import com.flowpay.backend.webhook.domain.WebhookDeliveryStatus;
import com.flowpay.backend.webhook.domain.WebhookResourceType;
import java.time.Instant;

/** Webhook-owned projection. Internal references are never mapped into HTTP responses. */
public record WebhookDeliveryView(
        long internalId, long endpointInternalId, String publicId, String endpointId,
        String eventId, String eventType, WebhookResourceType resourceType, String resourceId,
        WebhookDeliveryStatus status, int attemptCount, Instant nextAttemptAt, Instant deliveredAt,
        Integer lastHttpStatus, String lastError, Instant createdAt, Instant updatedAt
) {
}
