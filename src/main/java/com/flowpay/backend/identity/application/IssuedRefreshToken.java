package com.flowpay.backend.identity.application;

import java.time.Instant;
import java.util.Objects;

public record IssuedRefreshToken(String value, Instant expiresAt) {

    public IssuedRefreshToken {
        Objects.requireNonNull(value, "value must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("value must not be blank");
        }
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
    }

    @Override
    public String toString() {
        return "IssuedRefreshToken[value=[REDACTED], expiresAt=" + expiresAt + "]";
    }
}
