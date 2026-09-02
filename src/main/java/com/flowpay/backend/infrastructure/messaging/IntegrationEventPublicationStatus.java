package com.flowpay.backend.infrastructure.messaging;

public enum IntegrationEventPublicationStatus {
    CONFIRMED,
    NEGATIVE_ACK,
    UNROUTABLE,
    CONFIRM_TIMEOUT,
    TRANSPORT_FAILURE
}
