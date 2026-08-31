package com.flowpay.backend.refund.application;

import com.flowpay.backend.refund.domain.RefundProviderOutcome;

import java.util.Objects;

public record RefundProviderResult(
        String provider,
        RefundProviderOutcome outcome,
        String providerRefundId,
        String failureCode,
        String failureMessage
) {

    public RefundProviderResult {
        provider = requireText(provider, "provider");
        outcome = Objects.requireNonNull(outcome, "outcome must not be null");
        providerRefundId = normalizeOptionalText(providerRefundId);
        failureCode = normalizeOptionalText(failureCode);
        failureMessage = normalizeOptionalText(failureMessage);
        validateMetadata(outcome, providerRefundId, failureCode, failureMessage);
    }

    private static void validateMetadata(
            RefundProviderOutcome outcome,
            String providerRefundId,
            String failureCode,
            String failureMessage
    ) {
        if (outcome == RefundProviderOutcome.SUCCESS) {
            if (providerRefundId == null) {
                throw new IllegalArgumentException(
                        "a successful provider result must have a providerRefundId"
                );
            }
            if (failureCode != null || failureMessage != null) {
                throw new IllegalArgumentException(
                        "a successful provider result must not have failure metadata"
                );
            }
            return;
        }
        if (failureCode == null || failureMessage == null) {
            throw new IllegalArgumentException(
                    "a non-success provider result must have normalized failure metadata"
            );
        }
    }

    private static String requireText(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }

    private static String normalizeOptionalText(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
