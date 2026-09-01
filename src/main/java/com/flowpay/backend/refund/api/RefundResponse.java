package com.flowpay.backend.refund.api;

import com.flowpay.backend.refund.domain.RefundStatus;

import java.time.Instant;

public record RefundResponse(
        String id,
        String paymentId,
        long amount,
        String currency,
        RefundStatus status,
        String reason,
        String provider,
        String providerRefundId,
        String failureCode,
        String failureMessage,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt
) {
}
