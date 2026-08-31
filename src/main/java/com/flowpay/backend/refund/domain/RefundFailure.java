package com.flowpay.backend.refund.domain;

import java.util.Objects;

public record RefundFailure(String code, String message) {

    public RefundFailure {
        code = requireText(code, "code");
        message = requireText(message, "message");
    }

    public static RefundFailure of(String code, String message) {
        return new RefundFailure(code, message);
    }

    private static String requireText(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }
}
