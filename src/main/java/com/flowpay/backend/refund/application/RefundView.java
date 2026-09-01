package com.flowpay.backend.refund.application;

import com.flowpay.backend.refund.domain.RefundStatus;

import java.time.Instant;

public record RefundView(
        String publicId,
        String paymentPublicId,
        long amountMinor,
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
