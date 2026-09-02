package com.flowpay.backend.infrastructure.messaging;

import com.flowpay.backend.infrastructure.messaging.outbox.IntegrationEventEnvelope;

public interface IntegrationEventTransportPublisher {

    IntegrationEventPublicationResult publish(IntegrationEventEnvelope envelope);
}
