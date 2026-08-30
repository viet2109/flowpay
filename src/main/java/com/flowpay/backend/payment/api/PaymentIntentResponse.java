package com.flowpay.backend.payment.api;

import com.flowpay.backend.payment.domain.PaymentStatus;

import java.time.Instant;

public record PaymentIntentResponse(
        String id,
        String orderId,
        String description,
        long amount,
        String currency,
        PaymentStatus status,
        long refundedAmount,
        long refundableAmount,
        Instant createdAt,
        Instant updatedAt
) {
}
