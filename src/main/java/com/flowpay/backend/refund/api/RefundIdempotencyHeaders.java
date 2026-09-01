package com.flowpay.backend.refund.api;

final class RefundIdempotencyHeaders {

    static final String KEY = "Idempotency-Key";
    static final String REPLAYED = "Idempotency-Replayed";

    private RefundIdempotencyHeaders() {
    }
}
