package com.flowpay.backend.webhook.infrastructure.persistence;

import com.flowpay.backend.webhook.domain.WebhookDelivery;

final class WebhookDeliveryPersistenceMapper {
    private WebhookDeliveryPersistenceMapper() {
    }

    static WebhookDeliveryEntity toEntity(WebhookDelivery delivery) {
        return new WebhookDeliveryEntity(delivery.internalId(), delivery.publicId(), delivery.webhookEventId(),
                delivery.webhookEndpointId(), delivery.status(), delivery.attemptCount(), delivery.nextAttemptAt(),
                delivery.leaseExpiresAt(), delivery.deliveredAt(), delivery.lastHttpStatus(), delivery.lastError(),
                delivery.createdAt(), delivery.updatedAt(), delivery.version());
    }

    static WebhookDelivery toDomain(WebhookDeliveryEntity delivery) {
        return WebhookDelivery.rehydrate(delivery.id(), delivery.publicId(), delivery.webhookEventId(),
                delivery.webhookEndpointId(), delivery.status(), delivery.attemptCount(), delivery.nextAttemptAt(),
                delivery.leaseExpiresAt(), delivery.deliveredAt(), delivery.lastHttpStatus(), delivery.lastError(),
                delivery.createdAt(), delivery.updatedAt(), delivery.version());
    }
}
