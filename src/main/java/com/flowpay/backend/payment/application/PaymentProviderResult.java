package com.flowpay.backend.payment.application;

import com.flowpay.backend.payment.domain.ProviderOutcome;

import java.util.Objects;

public record PaymentProviderResult(
        String provider,
        ProviderOutcome outcome,
        String providerTransactionId,
        String failureCode,
        String failureMessage
) {

    public PaymentProviderResult {
        provider = requireText(provider, "provider");
        Objects.requireNonNull(outcome, "outcome must not be null");
        providerTransactionId = normalizeOptionalText(providerTransactionId);
        failureCode = normalizeOptionalText(failureCode);
        failureMessage = normalizeOptionalText(failureMessage);
        validateMetadata(outcome, providerTransactionId, failureCode, failureMessage);
    }

    private static void validateMetadata(
            ProviderOutcome outcome,
            String providerTransactionId,
            String failureCode,
            String failureMessage
    ) {
        if (outcome == ProviderOutcome.SUCCESS) {
            if (providerTransactionId == null) {
                throw new IllegalArgumentException(
                        "a successful provider result must have a providerTransactionId"
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
