package com.flowpay.backend.idempotency.domain;

import java.util.Objects;

public record IdempotencyKey(String value) {

    public static final int MAX_LENGTH = 255;

    public IdempotencyKey {
        Objects.requireNonNull(value, "idempotencyKey must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey must not be blank");
        }
        if (value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "idempotencyKey must not exceed " + MAX_LENGTH + " characters"
            );
        }
    }

    public static IdempotencyKey of(String value) {
        return new IdempotencyKey(value);
    }

    @Override
    public String toString() {
        return "IdempotencyKey[REDACTED]";
    }
}
