package com.flowpay.backend.refund.application.event;

public interface RefundIntegrationEventPublisher {

    void publish(RefundSucceededEventV1 event);
    void publish(RefundProcessingEventV1 event);
    void publish(RefundFailedEventV1 event);
}
