package com.flowpay.backend.refund.domain;

public record RefundReason(String value) {

    public static final int MAX_LENGTH = 255;

    public RefundReason {
        if (value != null) {
            value = value.trim();
            if (value.isEmpty()) {
                value = null;
            } else if (value.length() > MAX_LENGTH) {
                throw new IllegalArgumentException(
                        "reason must not exceed " + MAX_LENGTH + " characters"
                );
            }
        }
    }

    public static RefundReason of(String value) {
        return new RefundReason(value);
    }
}
