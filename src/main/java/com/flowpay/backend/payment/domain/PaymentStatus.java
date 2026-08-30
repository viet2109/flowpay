package com.flowpay.backend.payment.domain;

public enum PaymentStatus {
    CREATED,
    PROCESSING,
    SUCCEEDED,
    FAILED,
    PARTIALLY_REFUNDED,
    REFUNDED
}
