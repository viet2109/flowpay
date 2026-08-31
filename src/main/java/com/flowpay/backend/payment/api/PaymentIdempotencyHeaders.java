package com.flowpay.backend.payment.api;

final class PaymentIdempotencyHeaders {

    static final String KEY = "Idempotency-Key";
    static final String REPLAYED = "Idempotency-Replayed";

    private PaymentIdempotencyHeaders() {
    }
}
