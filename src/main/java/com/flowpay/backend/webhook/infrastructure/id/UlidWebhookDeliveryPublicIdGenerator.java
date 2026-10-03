package com.flowpay.backend.webhook.infrastructure.id;

import com.flowpay.backend.webhook.application.WebhookDeliveryPublicIdGenerator;
import com.github.f4b6a3.ulid.UlidCreator;
import org.springframework.stereotype.Component;

@Component
final class UlidWebhookDeliveryPublicIdGenerator implements WebhookDeliveryPublicIdGenerator {
    @Override
    public String nextId() {
        return "wdl_" + UlidCreator.getMonotonicUlid();
    }
}
