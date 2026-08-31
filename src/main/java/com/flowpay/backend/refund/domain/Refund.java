package com.flowpay.backend.refund.domain;

import com.flowpay.backend.common.money.Money;

import java.time.Instant;
import java.util.Objects;

public final class Refund {

    private static final String PUBLIC_ID_PREFIX = "re_";
    private static final int PUBLIC_ID_MAX_LENGTH = 64;

    private final Long internalId;
    private final String publicId;
    private final Long merchantId;
    private final Long paymentIntentId;
    private final Money amount;
    private RefundStatus status;
    private final RefundReason reason;
    private final String provider;
    private String providerRefundId;
    private RefundFailure failure;
    private final Instant createdAt;
    private Instant updatedAt;
    private Instant completedAt;
    private final long version;

    private Refund(
            Long internalId,
            String publicId,
            Long merchantId,
            Long paymentIntentId,
            Money amount,
            RefundStatus status,
            RefundReason reason,
            String provider,
            String providerRefundId,
            RefundFailure failure,
            Instant createdAt,
            Instant updatedAt,
            Instant completedAt,
            long version
    ) {
        this.internalId = validateInternalId(internalId);
        this.publicId = validatePublicId(publicId);
        this.merchantId = validatePositiveId(merchantId, "merchantId");
        this.paymentIntentId = validatePositiveId(paymentIntentId, "paymentIntentId");
        this.amount = requirePositiveAmount(amount);
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.reason = Objects.requireNonNull(reason, "reason must not be null");
        this.provider = requireText(provider, "provider");
        this.providerRefundId = normalizeOptionalText(providerRefundId);
        this.failure = failure;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        this.completedAt = completedAt;
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
        this.version = version;
        validateTimestamps();
        validateLifecycleState();
    }

    public static Refund create(
            String publicId,
            long merchantId,
            long paymentIntentId,
            Money amount,
            RefundReason reason,
            String provider,
            Instant now
    ) {
        Instant createdAt = Objects.requireNonNull(now, "now must not be null");
        return new Refund(
                null,
                publicId,
                merchantId,
                paymentIntentId,
                amount,
                RefundStatus.CREATED,
                reason,
                provider,
                null,
                null,
                createdAt,
                createdAt,
                null,
                0L
        );
    }

    public static Refund rehydrate(
            long internalId,
            String publicId,
            long merchantId,
            long paymentIntentId,
            Money amount,
            RefundStatus status,
            RefundReason reason,
            String provider,
            String providerRefundId,
            String failureCode,
            String failureMessage,
            Instant createdAt,
            Instant updatedAt,
            Instant completedAt,
            long version
    ) {
        return new Refund(
                internalId,
                publicId,
                merchantId,
                paymentIntentId,
                amount,
                status,
                reason,
                provider,
                providerRefundId,
                rehydrateFailure(failureCode, failureMessage),
                createdAt,
                updatedAt,
                completedAt,
                version
        );
    }

    public void startProcessing(Instant changedAt) {
        requireStatus(RefundStatus.CREATED, RefundStatus.PROCESSING);
        Instant transitionTime = validateChangeTime(changedAt, "changedAt");
        status = RefundStatus.PROCESSING;
        updatedAt = transitionTime;
    }

    public void markSucceeded(String providerRefundId, Instant completedAt) {
        requireStatus(RefundStatus.PROCESSING, RefundStatus.SUCCEEDED);
        String normalizedProviderRefundId = requireText(providerRefundId, "providerRefundId");
        Instant completionTime = validateChangeTime(completedAt, "completedAt");
        this.providerRefundId = normalizedProviderRefundId;
        failure = null;
        status = RefundStatus.SUCCEEDED;
        updatedAt = completionTime;
        this.completedAt = completionTime;
    }

    public void markFailed(
            String providerRefundId,
            RefundFailure failure,
            Instant completedAt
    ) {
        requireStatus(RefundStatus.PROCESSING, RefundStatus.FAILED);
        String normalizedProviderRefundId = normalizeOptionalText(providerRefundId);
        RefundFailure normalizedFailure = Objects.requireNonNull(
                failure,
                "failure must not be null"
        );
        Instant completionTime = validateChangeTime(completedAt, "completedAt");
        this.providerRefundId = normalizedProviderRefundId;
        this.failure = normalizedFailure;
        status = RefundStatus.FAILED;
        updatedAt = completionTime;
        this.completedAt = completionTime;
    }

    public void recordUnknownProviderResult(
            String providerRefundId,
            RefundFailure failure,
            Instant observedAt
    ) {
        requireStatus(RefundStatus.PROCESSING, RefundStatus.PROCESSING);
        String normalizedProviderRefundId = normalizeOptionalText(providerRefundId);
        RefundFailure normalizedFailure = Objects.requireNonNull(
                failure,
                "failure must not be null"
        );
        Instant observationTime = validateChangeTime(observedAt, "observedAt");
        this.providerRefundId = normalizedProviderRefundId;
        this.failure = normalizedFailure;
        updatedAt = observationTime;
    }

    private void validateTimestamps() {
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not be before createdAt");
        }
        if (completedAt != null) {
            if (completedAt.isBefore(createdAt)) {
                throw new IllegalArgumentException("completedAt must not be before createdAt");
            }
            if (updatedAt.isBefore(completedAt)) {
                throw new IllegalArgumentException("updatedAt must not be before completedAt");
            }
        }
    }

    private void validateLifecycleState() {
        switch (status) {
            case CREATED -> {
                requireNoCompletion();
                requireNoProviderMetadata();
            }
            case PROCESSING -> requireNoCompletion();
            case SUCCEEDED -> {
                requireCompletion();
                if (providerRefundId == null) {
                    throw new IllegalArgumentException(
                            "a succeeded Refund must have a providerRefundId"
                    );
                }
                if (failure != null) {
                    throw new IllegalArgumentException(
                            "a succeeded Refund must not have failure metadata"
                    );
                }
            }
            case FAILED -> {
                requireCompletion();
                if (failure == null) {
                    throw new IllegalArgumentException(
                            "a failed Refund must have failure metadata"
                    );
                }
            }
        }
    }

    private void requireNoCompletion() {
        if (completedAt != null) {
            throw new IllegalArgumentException(
                    "a non-terminal Refund must not have completedAt"
            );
        }
    }

    private void requireCompletion() {
        if (completedAt == null) {
            throw new IllegalArgumentException("a terminal Refund must have completedAt");
        }
    }

    private void requireNoProviderMetadata() {
        if (providerRefundId != null || failure != null) {
            throw new IllegalArgumentException(
                    "a created Refund must not have provider result metadata"
            );
        }
    }

    private void requireStatus(RefundStatus expected, RefundStatus target) {
        if (status != expected) {
            throw new IllegalStateException(
                    "Refund cannot transition from " + status + " to " + target
            );
        }
    }

    private Instant validateChangeTime(Instant value, String fieldName) {
        Instant changeTime = Objects.requireNonNull(value, fieldName + " must not be null");
        if (changeTime.isBefore(updatedAt)) {
            throw new IllegalArgumentException(fieldName + " must not be before updatedAt");
        }
        return changeTime;
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
                    "publicId must start with re_ and contain an identifier"
            );
        }
        if (value.length() > PUBLIC_ID_MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "publicId must not exceed " + PUBLIC_ID_MAX_LENGTH + " characters"
            );
        }
        return value;
    }

    private static Long validatePositiveId(Long value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " must not be null");
        if (value <= 0) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
        return value;
    }

    private static Money requirePositiveAmount(Money amount) {
        Money value = Objects.requireNonNull(amount, "amount must not be null");
        if (!value.isPositive()) {
            throw new IllegalArgumentException("amount must be positive");
        }
        return value;
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

    private static RefundFailure rehydrateFailure(String failureCode, String failureMessage) {
        String normalizedCode = normalizeOptionalText(failureCode);
        String normalizedMessage = normalizeOptionalText(failureMessage);
        if (normalizedCode == null && normalizedMessage == null) {
            return null;
        }
        if (normalizedCode == null || normalizedMessage == null) {
            throw new IllegalArgumentException(
                    "failureCode and failureMessage must both be present or both be absent"
            );
        }
        return RefundFailure.of(normalizedCode, normalizedMessage);
    }

    public Long internalId() {
        return internalId;
    }

    public String publicId() {
        return publicId;
    }

    public Long merchantId() {
        return merchantId;
    }

    public Long paymentIntentId() {
        return paymentIntentId;
    }

    public Money amount() {
        return amount;
    }

    public RefundStatus status() {
        return status;
    }

    public RefundReason reason() {
        return reason;
    }

    public String provider() {
        return provider;
    }

    public String providerRefundId() {
        return providerRefundId;
    }

    public RefundFailure failure() {
        return failure;
    }

    public String failureCode() {
        return failure == null ? null : failure.code();
    }

    public String failureMessage() {
        return failure == null ? null : failure.message();
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public Instant completedAt() {
        return completedAt;
    }

    public long version() {
        return version;
    }
}
