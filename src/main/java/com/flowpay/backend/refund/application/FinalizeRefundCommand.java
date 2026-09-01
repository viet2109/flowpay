package com.flowpay.backend.refund.application;

import java.util.Objects;

public record FinalizeRefundCommand(
        long merchantInternalId,
        String refundPublicId,
        String paymentPublicId,
        RefundProviderResult providerResult
) {

    public FinalizeRefundCommand {
        if (merchantInternalId <= 0) {
            throw new IllegalArgumentException("merchantInternalId must be positive");
        }
        refundPublicId = requireText(refundPublicId, "refundPublicId");
        paymentPublicId = requireText(paymentPublicId, "paymentPublicId");
        providerResult = Objects.requireNonNull(
                providerResult,
                "providerResult must not be null"
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
