package com.flowpay.backend.refund.application;

import com.flowpay.backend.common.money.Money;
import com.flowpay.backend.refund.domain.RefundReason;

import java.util.Objects;

public record PreparedRefund(
        long idempotencyExecutionId,
        long merchantInternalId,
        String refundPublicId,
        String paymentPublicId,
        Money amount,
        RefundReason reason,
        String provider,
        String providerTransactionId
) {

    public PreparedRefund {
        if (idempotencyExecutionId <= 0) {
            throw new IllegalArgumentException("idempotencyExecutionId must be positive");
        }
        if (merchantInternalId <= 0) {
            throw new IllegalArgumentException("merchantInternalId must be positive");
        }
        refundPublicId = requireText(refundPublicId, "refundPublicId");
        paymentPublicId = requireText(paymentPublicId, "paymentPublicId");
        amount = Objects.requireNonNull(amount, "amount must not be null");
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("amount must be positive");
        }
        reason = Objects.requireNonNull(reason, "reason must not be null");
        provider = requireText(provider, "provider");
        providerTransactionId = requireText(
                providerTransactionId,
                "providerTransactionId"
        );
    }

    public RefundProviderRequest toProviderRequest() {
        return new RefundProviderRequest(
                refundPublicId,
                paymentPublicId,
                providerTransactionId,
                amount,
                reason
        );
    }

    private static String requireText(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }
}
