package com.flowpay.backend.webhook.api;

import com.flowpay.backend.webhook.domain.WebhookDeliveryStatus;
import com.flowpay.backend.webhook.domain.WebhookResourceType;
import java.time.Instant;

public record WebhookDeliveryResponse(
        String id, String endpointId, String eventId, String eventType,
        WebhookResourceType resourceType, String resourceId, WebhookDeliveryStatus status,
        int attemptCount, Instant nextAttemptAt, Instant deliveredAt, Integer lastHttpStatus,
        String lastError, Instant createdAt, Instant updatedAt
) {
}
