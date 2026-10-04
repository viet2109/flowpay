package com.flowpay.backend.webhook.infrastructure.id;

import com.flowpay.backend.webhook.application.WebhookEventPublicIdGenerator;
import com.github.f4b6a3.ulid.UlidCreator;
import org.springframework.stereotype.Component;

@Component
final class UlidWebhookEventPublicIdGenerator implements WebhookEventPublicIdGenerator {
    @Override
    public String nextId() {
        return "evt_" + UlidCreator.getMonotonicUlid();
    }
}
