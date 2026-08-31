package com.flowpay.backend.payment.application;

import com.flowpay.backend.payment.domain.PaymentStatus;
import com.flowpay.backend.payment.domain.PaymentTransactionStatus;

import java.util.Objects;

public record ConfirmPaymentResponseSnapshot(
        String paymentId,
        PaymentStatus paymentStatus,
        String transactionId,
        PaymentTransactionStatus transactionStatus,
        String provider,
        String providerTransactionId,
        String failureCode,
        String failureMessage
) {

    private static final int COMPLETED_HTTP_STATUS = 200;
    private static final int UNKNOWN_HTTP_STATUS = 202;

    public ConfirmPaymentResponseSnapshot {
        paymentId = requirePublicId(paymentId, "pi_", "paymentId");
        Objects.requireNonNull(paymentStatus, "paymentStatus must not be null");
        transactionId = requirePublicId(transactionId, "ptxn_", "transactionId");
        Objects.requireNonNull(transactionStatus, "transactionStatus must not be null");
        provider = requireText(provider, "provider");
        providerTransactionId = normalizeOptionalText(providerTransactionId);
        failureCode = normalizeOptionalText(failureCode);
        failureMessage = normalizeOptionalText(failureMessage);
        validateOutcome(
                paymentStatus,
                transactionStatus,
                providerTransactionId,
                failureCode,
                failureMessage
        );
    }

    public int httpStatus() {
        return transactionStatus == PaymentTransactionStatus.UNKNOWN
                ? UNKNOWN_HTTP_STATUS
                : COMPLETED_HTTP_STATUS;
    }

    private static void validateOutcome(
            PaymentStatus paymentStatus,
            PaymentTransactionStatus transactionStatus,
            String providerTransactionId,
            String failureCode,
            String failureMessage
    ) {
        if (paymentStatus == PaymentStatus.SUCCEEDED
                && transactionStatus == PaymentTransactionStatus.SUCCEEDED) {
            if (providerTransactionId == null) {
                throw new IllegalArgumentException(
                        "a successful confirm snapshot must have a providerTransactionId"
                );
            }
            if (failureCode != null || failureMessage != null) {
                throw new IllegalArgumentException(
                        "a successful confirm snapshot must not have failure metadata"
                );
            }
            return;
        }
        boolean failed = paymentStatus == PaymentStatus.FAILED
                && transactionStatus == PaymentTransactionStatus.FAILED;
        boolean unknown = paymentStatus == PaymentStatus.PROCESSING
                && transactionStatus == PaymentTransactionStatus.UNKNOWN;
        if (!failed && !unknown) {
            throw new IllegalArgumentException("confirm snapshot has an invalid state pair");
        }
        if (failureCode == null || failureMessage == null) {
            throw new IllegalArgumentException(
                    "a non-success confirm snapshot must have safe failure metadata"
            );
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
