package com.flowpay.backend.payment.application;

import com.flowpay.backend.payment.domain.PaymentStatus;

import java.time.Instant;

public record PaymentIntentView(
        String publicId,
        String orderId,
        String description,
        long amountMinor,
        String currency,
        PaymentStatus status,
        long refundedAmountMinor,
        long refundableAmountMinor,
        Instant createdAt,
        Instant updatedAt
) {
}
