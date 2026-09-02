package com.flowpay.backend.infrastructure.messaging.outbox.id;

import com.flowpay.backend.infrastructure.messaging.outbox.IntegrationEventIdGenerator;
import com.github.f4b6a3.ulid.UlidCreator;
import org.springframework.stereotype.Component;

@Component
final class UlidIntegrationEventIdGenerator implements IntegrationEventIdGenerator {

    @Override
    public String nextId() {
        return "ievt_" + UlidCreator.getMonotonicUlid();
    }
}
