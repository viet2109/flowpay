package com.flowpay.backend.idempotency.application;

public final class IdempotencyRequestInProgressException extends RuntimeException {

    public IdempotencyRequestInProgressException() {
        super("A request with this idempotency key is still processing.");
    }
}
