package com.flowpay.backend.refund.application;

import com.flowpay.backend.common.money.Money;
import com.flowpay.backend.refund.domain.RefundReason;

import java.util.Objects;

public record RefundProviderRequest(
        String refundPublicId,
        String paymentPublicId,
        String providerTransactionId,
        Money amount,
        RefundReason reason
) {

    public RefundProviderRequest {
        refundPublicId = requireText(refundPublicId, "refundPublicId");
        paymentPublicId = requireText(paymentPublicId, "paymentPublicId");
        providerTransactionId = requireText(
                providerTransactionId,
                "providerTransactionId"
        );
        amount = Objects.requireNonNull(amount, "amount must not be null");
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("amount must be positive");
        }
        reason = Objects.requireNonNull(reason, "reason must not be null");
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
