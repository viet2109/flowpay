package com.flowpay.backend.payment.application;

import com.flowpay.backend.payment.domain.PaymentStatus;

import java.time.Instant;
import java.util.Currency;
import java.util.Objects;

public record CreatePaymentResponseSnapshot(
        String id,
        String orderId,
        String description,
        long amount,
        String currency,
        PaymentStatus status,
        long refundedAmount,
        long refundableAmount,
        Instant createdAt
) {

    public CreatePaymentResponseSnapshot {
        if (id == null || !id.startsWith("pi_") || id.length() == 3) {
            throw new IllegalArgumentException("id must be a PaymentIntent public ID");
        }
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        currency = Currency.getInstance(Objects.requireNonNull(
                currency,
                "currency must not be null"
        )).getCurrencyCode();
        if (status != PaymentStatus.CREATED) {
            throw new IllegalArgumentException("create snapshot status must be CREATED");
        }
        if (refundedAmount != 0 || refundableAmount != 0) {
            throw new IllegalArgumentException(
                    "create snapshot refund amounts must be zero"
            );
        }
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }
}
