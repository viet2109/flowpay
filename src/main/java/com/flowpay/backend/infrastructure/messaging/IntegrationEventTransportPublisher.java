package com.flowpay.backend.infrastructure.messaging;

import com.flowpay.backend.infrastructure.messaging.outbox.IntegrationEventEnvelope;

public interface IntegrationEventTransportPublisher {

    default IntegrationEventPublicationResult publish(IntegrationEventEnvelope envelope) {
        return publish(envelope, null);
    }

    /** Optional transport metadata is deliberately separate from the JSON envelope. */
    IntegrationEventPublicationResult publish(IntegrationEventEnvelope envelope, String traceparent);
}
