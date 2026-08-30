package com.flowpay.backend.payment.domain;

import java.time.Instant;
import java.util.Objects;

public final class PaymentTransaction {

    private static final String PUBLIC_ID_PREFIX = "ptxn_";
    private static final int PUBLIC_ID_MAX_LENGTH = 64;

    private final Long internalId;
    private final String publicId;
    private final Long paymentIntentId;
    private final int attemptNo;
    private final String provider;
    private String providerTransactionId;
    private PaymentTransactionStatus status;
    private String failureCode;
    private String failureMessage;
    private final Instant startedAt;
    private Instant completedAt;
    private final long version;

    private PaymentTransaction(
            Long internalId,
            String publicId,
            Long paymentIntentId,
            int attemptNo,
            String provider,
            String providerTransactionId,
            PaymentTransactionStatus status,
            String failureCode,
            String failureMessage,
            Instant startedAt,
            Instant completedAt,
            long version
    ) {
        this.internalId = validateInternalId(internalId);
        this.publicId = validatePublicId(publicId);
        this.paymentIntentId = validatePaymentIntentId(paymentIntentId);
        this.attemptNo = validateAttemptNo(attemptNo);
        this.provider = requireText(provider, "provider");
        this.providerTransactionId = normalizeOptionalText(providerTransactionId);
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.failureCode = normalizeOptionalText(failureCode);
        this.failureMessage = normalizeOptionalText(failureMessage);
        this.startedAt = startedAt;
        this.completedAt = completedAt;
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
        this.version = version;
        validateLifecycleState();
    }

    public static PaymentTransaction createProcessing(
            String publicId,
            long paymentIntentId,
            int attemptNo,
            String provider,
            Instant startedAt
    ) {
        return new PaymentTransaction(
                null,
                publicId,
                paymentIntentId,
                attemptNo,
                provider,
                null,
                PaymentTransactionStatus.PROCESSING,
                null,
                null,
                Objects.requireNonNull(startedAt, "startedAt must not be null"),
                null,
                0L
        );
    }

    public static PaymentTransaction rehydrate(
            long internalId,
            String publicId,
            long paymentIntentId,
            int attemptNo,
            String provider,
            String providerTransactionId,
            PaymentTransactionStatus status,
            String failureCode,
            String failureMessage,
            Instant startedAt,
            Instant completedAt,
            long version
    ) {
        return new PaymentTransaction(
                internalId,
                publicId,
                paymentIntentId,
                attemptNo,
                provider,
                providerTransactionId,
                status,
                failureCode,
                failureMessage,
                startedAt,
                completedAt,
                version
        );
    }

    public void markSucceeded(String providerTransactionId, Instant completedAt) {
        complete(
                PaymentTransactionStatus.SUCCEEDED,
                providerTransactionId,
                null,
                null,
                completedAt
        );
    }

    public void markFailed(
            String providerTransactionId,
            String failureCode,
            String failureMessage,
            Instant completedAt
    ) {
        complete(
                PaymentTransactionStatus.FAILED,
                providerTransactionId,
                failureCode,
                failureMessage,
                completedAt
        );
    }

    public void markUnknown(
            String providerTransactionId,
            String failureCode,
            String failureMessage,
            Instant completedAt
    ) {
        complete(
                PaymentTransactionStatus.UNKNOWN,
                providerTransactionId,
                failureCode,
                failureMessage,
                completedAt
        );
    }

    private void complete(
            PaymentTransactionStatus target,
            String providerTransactionId,
            String failureCode,
            String failureMessage,
            Instant completedAt
    ) {
        if (status != PaymentTransactionStatus.PROCESSING) {
            throw new IllegalStateException(
                    "PaymentTransaction cannot transition from " + status + " to " + target
            );
        }
        Instant completionTime = Objects.requireNonNull(completedAt, "completedAt must not be null");
        if (completionTime.isBefore(startedAt)) {
            throw new IllegalArgumentException("completedAt must not be before startedAt");
        }
        this.providerTransactionId = normalizeOptionalText(providerTransactionId);
        this.failureCode = normalizeOptionalText(failureCode);
        this.failureMessage = normalizeOptionalText(failureMessage);
        this.completedAt = completionTime;
        this.status = target;
    }

    private void validateLifecycleState() {
        switch (status) {
            case PENDING -> {
                if (startedAt != null || completedAt != null) {
                    throw new IllegalArgumentException(
                            "a pending transaction must not have lifecycle timestamps"
                    );
                }
                requireNoFailureMetadata();
            }
            case PROCESSING -> {
                if (startedAt == null) {
                    throw new IllegalArgumentException("a processing transaction must have startedAt");
                }
                if (completedAt != null) {
                    throw new IllegalArgumentException(
                            "a processing transaction must not have completedAt"
                    );
                }
                requireNoFailureMetadata();
            }
            case SUCCEEDED -> {
                requireValidCompletionTimestamps();
                requireNoFailureMetadata();
            }
            case FAILED, UNKNOWN -> requireValidCompletionTimestamps();
        }
    }

    private void requireNoFailureMetadata() {
        if (failureCode != null || failureMessage != null) {
            throw new IllegalArgumentException(
                    status + " transaction must not have failure metadata"
            );
        }
    }

    private void requireValidCompletionTimestamps() {
        if (startedAt == null || completedAt == null) {
            throw new IllegalArgumentException(
                    "a terminal transaction must have startedAt and completedAt"
            );
        }
        if (completedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("completedAt must not be before startedAt");
        }
    }

    private static Long validateInternalId(Long internalId) {
        if (internalId != null && internalId <= 0) {
            throw new IllegalArgumentException("internalId must be positive");
        }
        return internalId;
    }

    private static String validatePublicId(String publicId) {
        String value = Objects.requireNonNull(publicId, "publicId must not be null");
        if (!value.startsWith(PUBLIC_ID_PREFIX) || value.length() == PUBLIC_ID_PREFIX.length()) {
            throw new IllegalArgumentException(
                    "publicId must start with ptxn_ and contain an identifier"
            );
        }
        if (value.length() > PUBLIC_ID_MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "publicId must not exceed " + PUBLIC_ID_MAX_LENGTH + " characters"
            );
        }
        return value;
    }

    private static Long validatePaymentIntentId(Long paymentIntentId) {
        Objects.requireNonNull(paymentIntentId, "paymentIntentId must not be null");
        if (paymentIntentId <= 0) {
            throw new IllegalArgumentException("paymentIntentId must be positive");
        }
        return paymentIntentId;
    }

    private static int validateAttemptNo(int attemptNo) {
        if (attemptNo <= 0) {
            throw new IllegalArgumentException("attemptNo must be positive");
        }
        return attemptNo;
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

    public Long internalId() {
        return internalId;
    }

    public String publicId() {
        return publicId;
    }

    public Long paymentIntentId() {
        return paymentIntentId;
    }

    public int attemptNo() {
        return attemptNo;
    }

    public String provider() {
        return provider;
    }

    public String providerTransactionId() {
        return providerTransactionId;
    }

    public PaymentTransactionStatus status() {
        return status;
    }

    public String failureCode() {
        return failureCode;
    }

    public String failureMessage() {
        return failureMessage;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Instant completedAt() {
        return completedAt;
    }

    public long version() {
        return version;
    }
}
