package com.flowpay.backend.payment.domain;

import com.flowpay.backend.common.money.Money;

import java.time.Instant;
import java.util.Objects;

public final class PaymentIntent {

    private static final String PUBLIC_ID_PREFIX = "pi_";
    private static final int PUBLIC_ID_MAX_LENGTH = 64;

    private final Long internalId;
    private final String publicId;
    private final Long merchantId;
    private final String merchantOrderId;
    private final String description;
    private final Money amount;
    private PaymentStatus status;
    private Money refundedAmount;
    private Money refundReservedAmount;
    private final long version;
    private final Instant createdAt;
    private Instant updatedAt;

    private PaymentIntent(
            Long internalId,
            String publicId,
            Long merchantId,
            String merchantOrderId,
            String description,
            Money amount,
            PaymentStatus status,
            Money refundedAmount,
            Money refundReservedAmount,
            long version,
            Instant createdAt,
            Instant updatedAt
    ) {
        this.internalId = validateInternalId(internalId);
        this.publicId = validatePublicId(publicId);
        this.merchantId = validateMerchantId(merchantId);
        this.merchantOrderId = normalizeOptionalText(merchantOrderId);
        this.description = normalizeOptionalText(description);
        this.amount = requirePositiveAmount(amount);
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.refundedAmount = Objects.requireNonNull(refundedAmount, "refundedAmount must not be null");
        this.refundReservedAmount = Objects.requireNonNull(
                refundReservedAmount,
                "refundReservedAmount must not be null"
        );
        validateRefundAmounts(this.amount, this.refundedAmount, this.refundReservedAmount);
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
        this.version = version;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not be before createdAt");
        }
    }

    public static PaymentIntent create(
            String publicId,
            long merchantId,
            String merchantOrderId,
            String description,
            Money amount,
            Instant now
    ) {
        Money originalAmount = Objects.requireNonNull(amount, "amount must not be null");
        Money zero = new Money(0L, originalAmount.currency());
        Instant createdAt = Objects.requireNonNull(now, "now must not be null");
        return new PaymentIntent(
                null,
                publicId,
                merchantId,
                merchantOrderId,
                description,
                originalAmount,
                PaymentStatus.CREATED,
                zero,
                zero,
                0L,
                createdAt,
                createdAt
        );
    }

    public static PaymentIntent rehydrate(
            long internalId,
            String publicId,
            long merchantId,
            String merchantOrderId,
            String description,
            Money amount,
            PaymentStatus status,
            Money refundedAmount,
            Money refundReservedAmount,
            long version,
            Instant createdAt,
            Instant updatedAt
    ) {
        return new PaymentIntent(
                internalId,
                publicId,
                merchantId,
                merchantOrderId,
                description,
                amount,
                status,
                refundedAmount,
                refundReservedAmount,
                version,
                createdAt,
                updatedAt
        );
    }

    public void startProcessing(Instant changedAt) {
        transitionFrom(PaymentStatus.CREATED, PaymentStatus.PROCESSING, changedAt);
    }

    public void markSucceeded(Instant changedAt) {
        transitionFrom(PaymentStatus.PROCESSING, PaymentStatus.SUCCEEDED, changedAt);
    }

    public void markFailed(Instant changedAt) {
        transitionFrom(PaymentStatus.PROCESSING, PaymentStatus.FAILED, changedAt);
    }

    private void transitionFrom(PaymentStatus expected, PaymentStatus target, Instant changedAt) {
        if (status != expected) {
            throw new IllegalStateException(
                    "PaymentIntent cannot transition from " + status + " to " + target
            );
        }
        Instant transitionTime = Objects.requireNonNull(changedAt, "changedAt must not be null");
        if (transitionTime.isBefore(updatedAt)) {
            throw new IllegalArgumentException("changedAt must not be before updatedAt");
        }
        status = target;
        updatedAt = transitionTime;
    }

    private static Long validateInternalId(Long internalId) {
        if (internalId != null && internalId <= 0) {
            throw new IllegalArgumentException("internalId must be positive");
        }
        return internalId;
    }

    private static String validatePublicId(String publicId) {
        Objects.requireNonNull(publicId, "publicId must not be null");
        if (!publicId.startsWith(PUBLIC_ID_PREFIX) || publicId.length() == PUBLIC_ID_PREFIX.length()) {
            throw new IllegalArgumentException("publicId must start with pi_ and contain an identifier");
        }
        if (publicId.length() > PUBLIC_ID_MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "publicId must not exceed " + PUBLIC_ID_MAX_LENGTH + " characters"
            );
        }
        return publicId;
    }

    private static Long validateMerchantId(Long merchantId) {
        Objects.requireNonNull(merchantId, "merchantId must not be null");
        if (merchantId <= 0) {
            throw new IllegalArgumentException("merchantId must be positive");
        }
        return merchantId;
    }

    private static Money requirePositiveAmount(Money amount) {
        Money value = Objects.requireNonNull(amount, "amount must not be null");
        if (!value.isPositive()) {
            throw new IllegalArgumentException("amount must be positive");
        }
        return value;
    }

    private static void validateRefundAmounts(
            Money amount,
            Money refundedAmount,
            Money refundReservedAmount
    ) {
        if (!amount.sameCurrency(refundedAmount) || !amount.sameCurrency(refundReservedAmount)) {
            throw new IllegalArgumentException("refund amounts must use the payment currency");
        }
        if (refundedAmount.amountMinor() < 0 || refundReservedAmount.amountMinor() < 0) {
            throw new IllegalArgumentException("refund amounts must not be negative");
        }
        long allocatedAmount;
        try {
            allocatedAmount = Math.addExact(
                    refundedAmount.amountMinor(),
                    refundReservedAmount.amountMinor()
            );
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("refund amounts exceed the payment amount", exception);
        }
        if (allocatedAmount > amount.amountMinor()) {
            throw new IllegalArgumentException("refund amounts exceed the payment amount");
        }
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

    public Long merchantId() {
        return merchantId;
    }

    public String merchantOrderId() {
        return merchantOrderId;
    }

    public String description() {
        return description;
    }

    public Money amount() {
        return amount;
    }

    public PaymentStatus status() {
        return status;
    }

    public Money refundedAmount() {
        return refundedAmount;
    }

    public Money refundReservedAmount() {
        return refundReservedAmount;
    }

    public long version() {
        return version;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
