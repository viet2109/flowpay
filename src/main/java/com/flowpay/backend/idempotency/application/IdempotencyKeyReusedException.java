package com.flowpay.backend.idempotency.application;

public final class IdempotencyKeyReusedException extends RuntimeException {

    public IdempotencyKeyReusedException() {
        super("The idempotency key is already associated with a different request.");
    }
}
