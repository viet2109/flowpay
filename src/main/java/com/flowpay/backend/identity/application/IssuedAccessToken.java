package com.flowpay.backend.identity.application;

import java.time.Instant;
import java.util.Objects;

public record IssuedAccessToken(String value, long expiresInSeconds, Instant expiresAt) {

    public IssuedAccessToken {
        Objects.requireNonNull(value, "value must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("value must not be blank");
        }
        if (expiresInSeconds <= 0) {
            throw new IllegalArgumentException("expiresInSeconds must be positive");
        }
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
    }

    @Override
    public String toString() {
        return "IssuedAccessToken[value=[REDACTED], expiresInSeconds=" + expiresInSeconds
                + ", expiresAt=" + expiresAt + "]";
    }
}
