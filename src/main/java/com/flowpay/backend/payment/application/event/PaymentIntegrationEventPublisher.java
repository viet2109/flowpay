package com.flowpay.backend.payment.application.event;

@FunctionalInterface
public interface PaymentIntegrationEventPublisher {

    void publish(PaymentSucceededEventV1 event);
}
