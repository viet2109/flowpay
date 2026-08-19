package com.flowpay.backend.identity.domain;

import java.util.Locale;
import java.util.Objects;

public record Email(String value) {

    private static final int MAX_LENGTH = 320;

    public Email {
        Objects.requireNonNull(value, "email must not be null");
        value = value.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) {
            throw new IllegalArgumentException("email must not be blank");
        }
        if (value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("email must not exceed " + MAX_LENGTH + " characters");
        }
    }

    public static Email of(String value) {
        return new Email(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
