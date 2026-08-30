package com.flowpay.backend.payment.application;

import com.flowpay.backend.payment.domain.PaymentStatus;

import java.time.Instant;

public record CreatePaymentIntentResult(
        String publicId,
        String orderId,
        long amountMinor,
        String currency,
        PaymentStatus status,
        long refundedAmountMinor,
        long refundReservedAmountMinor,
        String description,
        Instant createdAt
) {
}
