package com.flowpay.backend.idempotency.application;

import java.util.Objects;

public record ConfirmPaymentFingerprint(
        int version,
        String paymentPublicId
) implements RequestFingerprintInput {

    public static final int CURRENT_VERSION = 1;

    public ConfirmPaymentFingerprint {
        if (version != CURRENT_VERSION) {
            throw new IllegalArgumentException(
                    "confirm payment fingerprint version must be " + CURRENT_VERSION
            );
        }
        Objects.requireNonNull(paymentPublicId, "paymentPublicId must not be null");
        paymentPublicId = paymentPublicId.trim();
        if (paymentPublicId.isEmpty()) {
            throw new IllegalArgumentException("paymentPublicId must not be blank");
        }
    }

    public static ConfirmPaymentFingerprint version1(String paymentPublicId) {
        return new ConfirmPaymentFingerprint(CURRENT_VERSION, paymentPublicId);
    }

    @Override
    public void appendTo(RequestFingerprintCanonicalizer canonicalizer) {
        canonicalizer.appendNumber("version", version);
        canonicalizer.appendText("paymentPublicId", paymentPublicId);
    }
}
