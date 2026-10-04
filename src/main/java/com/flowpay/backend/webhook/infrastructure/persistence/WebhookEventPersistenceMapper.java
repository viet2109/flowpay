package com.flowpay.backend.webhook.infrastructure.persistence;

import com.flowpay.backend.webhook.domain.WebhookEvent;
import com.flowpay.backend.webhook.domain.WebhookEventType;

final class WebhookEventPersistenceMapper {
    private WebhookEventPersistenceMapper() {
    }

    static WebhookEvent toDomain(WebhookEventEntity event) {
        return WebhookEvent.rehydrate(event.id(), event.publicId(), event.sourceEventId(), event.merchantId(),
                WebhookEventType.fromValue(event.eventType()), event.resourceType(), event.resourceId(),
                event.payload(), event.occurredAt(), event.createdAt());
    }
}
