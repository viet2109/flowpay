package com.flowpay.backend.payment.application;

public record OwnedPaymentSnapshot(
        long paymentInternalId,
        String paymentPublicId
) {

    public OwnedPaymentSnapshot {
        if (paymentInternalId <= 0) {
            throw new IllegalArgumentException("paymentInternalId must be positive");
        }
        if (paymentPublicId == null || paymentPublicId.isBlank()) {
            throw new IllegalArgumentException("paymentPublicId must not be blank");
        }
    }
}
