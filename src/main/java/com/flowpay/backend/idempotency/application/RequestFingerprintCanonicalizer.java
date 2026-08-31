package com.flowpay.backend.idempotency.application;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Length-prefixed canonical encoding shared by all idempotent operations.
 */
public final class RequestFingerprintCanonicalizer {

    private final StringBuilder canonical = new StringBuilder();

    public void appendNumber(String field, long value) {
        canonical.append(requireField(field))
                .append('=')
                .append(value)
                .append('\n');
    }

    public void appendText(String field, String value) {
        canonical.append(requireField(field)).append('=');
        if (value == null) {
            canonical.append("-1:");
        } else {
            canonical.append(value.getBytes(StandardCharsets.UTF_8).length)
                    .append(':')
                    .append(value);
        }
        canonical.append('\n');
    }

    String value() {
        return canonical.toString();
    }

    private static String requireField(String field) {
        Objects.requireNonNull(field, "field must not be null");
        if (field.isBlank()) {
            throw new IllegalArgumentException("field must not be blank");
        }
        return field;
    }
}
