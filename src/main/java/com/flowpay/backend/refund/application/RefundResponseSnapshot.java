package com.flowpay.backend.refund.application;

import com.flowpay.backend.refund.domain.RefundReason;
import com.flowpay.backend.refund.domain.RefundStatus;

import java.time.Instant;
import java.util.Currency;
import java.util.Objects;

public record RefundResponseSnapshot(
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

    private static final int CREATED_HTTP_STATUS = 201;
    private static final int ACCEPTED_HTTP_STATUS = 202;

    public RefundResponseSnapshot {
        id = requirePublicId(id, "re_", "id");
        paymentId = requirePublicId(paymentId, "pi_", "paymentId");
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        currency = Currency.getInstance(Objects.requireNonNull(
                currency,
                "currency must not be null"
        ).trim()).getCurrencyCode();
        status = Objects.requireNonNull(status, "status must not be null");
        reason = RefundReason.of(reason).value();
        provider = requireText(provider, "provider");
        providerRefundId = normalizeOptionalText(providerRefundId);
        failureCode = normalizeOptionalText(failureCode);
        failureMessage = normalizeOptionalText(failureMessage);
        createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        validateTimestamps(createdAt, updatedAt, completedAt);
        validateOutcome(
                status,
                providerRefundId,
                failureCode,
                failureMessage,
                completedAt
        );
    }

    public int httpStatus() {
        return status == RefundStatus.PROCESSING
                ? ACCEPTED_HTTP_STATUS
                : CREATED_HTTP_STATUS;
    }

    private static void validateOutcome(
            RefundStatus status,
            String providerRefundId,
            String failureCode,
            String failureMessage,
            Instant completedAt
    ) {
        boolean hasFailure = failureCode != null && failureMessage != null;
        boolean partialFailure = (failureCode == null) != (failureMessage == null);
        if (partialFailure) {
            throw new IllegalArgumentException(
                    "failureCode and failureMessage must both be present or both be absent"
            );
        }
        switch (status) {
            case SUCCEEDED -> {
                if (providerRefundId == null || hasFailure || completedAt == null) {
                    throw new IllegalArgumentException(
                            "a successful Refund snapshot requires provider completion metadata"
                    );
                }
            }
            case FAILED -> {
                if (!hasFailure || completedAt == null) {
                    throw new IllegalArgumentException(
                            "a failed Refund snapshot requires safe failure metadata"
                    );
                }
            }
            case PROCESSING -> {
                if (!hasFailure || completedAt != null) {
                    throw new IllegalArgumentException(
                            "a processing Refund snapshot requires pending failure metadata"
                    );
                }
            }
            case CREATED -> throw new IllegalArgumentException(
                    "a created Refund cannot be exposed as an orchestration snapshot"
            );
        }
    }

    private static void validateTimestamps(
            Instant createdAt,
            Instant updatedAt,
            Instant completedAt
    ) {
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not be before createdAt");
        }
        if (completedAt != null
                && (completedAt.isBefore(createdAt) || updatedAt.isBefore(completedAt))) {
            throw new IllegalArgumentException("completedAt must be within the Refund timeline");
        }
    }

    private static String requirePublicId(
            String value,
            String prefix,
            String fieldName
    ) {
        String normalized = requireText(value, fieldName);
        if (!normalized.startsWith(prefix) || normalized.length() == prefix.length()) {
            throw new IllegalArgumentException(
                    fieldName + " must be a public ID starting with " + prefix
            );
        }
        return normalized;
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
