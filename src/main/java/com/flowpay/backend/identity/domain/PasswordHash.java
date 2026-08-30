package com.flowpay.backend.identity.domain;

import java.util.Objects;

public final class PasswordHash {

    private static final int MAX_LENGTH = 255;
    private static final String REDACTED = "[REDACTED]";

    private final String value;

    private PasswordHash(String value) {
        Objects.requireNonNull(value, "password hash must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("password hash must not be blank");
        }
        if (value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("password hash must not exceed " + MAX_LENGTH + " characters");
        }
        this.value = value;
    }

    public static PasswordHash of(String value) {
        return new PasswordHash(value);
    }

    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof PasswordHash that && value.equals(that.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return REDACTED;
    }
}
