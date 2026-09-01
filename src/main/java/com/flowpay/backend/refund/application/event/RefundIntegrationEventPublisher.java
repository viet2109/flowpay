package com.flowpay.backend.refund.application.event;

@FunctionalInterface
public interface RefundIntegrationEventPublisher {

    void publish(RefundSucceededEventV1 event);
}
