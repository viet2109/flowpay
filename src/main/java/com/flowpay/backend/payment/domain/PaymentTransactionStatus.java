package com.flowpay.backend.payment.domain;

public enum PaymentTransactionStatus {
    PENDING,
    PROCESSING,
    SUCCEEDED,
    FAILED,
    UNKNOWN
}
