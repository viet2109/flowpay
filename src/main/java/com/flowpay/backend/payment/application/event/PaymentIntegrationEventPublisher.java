package com.flowpay.backend.payment.application.event;

public interface PaymentIntegrationEventPublisher {

    void publish(PaymentSucceededEventV1 event);
    void publish(PaymentProcessingEventV1 event);
    void publish(PaymentFailedEventV1 event);
}
