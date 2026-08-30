package com.flowpay.backend.idempotency.domain;

public enum IdempotencyOperation {
    PAYMENT_INTENT_CREATE,
    PAYMENT_INTENT_CONFIRM
}
