package com.flowpay.backend.webhook.infrastructure.id;

import com.flowpay.backend.webhook.application.WebhookEndpointPublicIdGenerator;
import com.github.f4b6a3.ulid.UlidCreator;
import org.springframework.stereotype.Component;

@Component
final class UlidWebhookEndpointPublicIdGenerator implements WebhookEndpointPublicIdGenerator {

    @Override
    public String nextId() {
        return "wep_" + UlidCreator.getMonotonicUlid();
    }
}
