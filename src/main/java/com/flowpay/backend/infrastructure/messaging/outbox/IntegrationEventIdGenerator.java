package com.flowpay.backend.infrastructure.messaging.outbox;

public interface IntegrationEventIdGenerator {

    String nextId();
}
