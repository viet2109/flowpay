package com.flowpay.backend.webhook.infrastructure.persistence;

import com.flowpay.backend.webhook.domain.WebhookEndpoint;
import com.flowpay.backend.webhook.domain.WebhookEventType;
import java.util.HashSet;
import java.util.stream.Collectors;

final class WebhookEndpointPersistenceMapper {
    private WebhookEndpointPersistenceMapper() {
    }

    static WebhookEndpointEntity toEntity(WebhookEndpoint endpoint) {
        return new WebhookEndpointEntity(endpoint.internalId(), endpoint.publicId(), endpoint.merchantId(),
                endpoint.url(), endpoint.secretCiphertext(), endpoint.status(),
                endpoint.subscribedEventTypes().stream().map(WebhookEventType::value)
                        .collect(Collectors.toCollection(HashSet::new)),
                endpoint.createdAt(), endpoint.updatedAt(), endpoint.version());
    }

    static WebhookEndpoint toDomain(WebhookEndpointEntity entity) {
        return WebhookEndpoint.rehydrate(entity.id(), entity.publicId(), entity.merchantId(),
                entity.url(), entity.secretCiphertext(), entity.status(),
                entity.events().stream().map(WebhookEventType::fromValue).toList(),
                entity.version(), entity.createdAt(), entity.updatedAt());
    }
}
