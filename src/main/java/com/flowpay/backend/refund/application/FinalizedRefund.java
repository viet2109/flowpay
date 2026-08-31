package com.flowpay.backend.refund.application;

import com.flowpay.backend.common.money.Money;
import com.flowpay.backend.refund.domain.RefundReason;
import com.flowpay.backend.refund.domain.RefundStatus;

import java.time.Instant;

public record FinalizedRefund(
        String refundPublicId,
        String paymentPublicId,
        Money amount,
        RefundStatus status,
        RefundReason reason,
        String provider,
        String providerRefundId,
        String failureCode,
        String failureMessage,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt
) {
}
