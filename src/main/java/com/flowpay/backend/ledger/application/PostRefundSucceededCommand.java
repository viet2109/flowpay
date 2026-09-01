package com.flowpay.backend.ledger.application;

import com.flowpay.backend.common.money.Money;

import java.time.Instant;
import java.util.Objects;

public record PostRefundSucceededCommand(
        long merchantInternalId,
        String refundPublicId,
        Money amount,
        Instant occurredAt
) {

    public PostRefundSucceededCommand {
        if (merchantInternalId <= 0) {
            throw new IllegalArgumentException("merchantInternalId must be positive");
        }
        refundPublicId = requireText(refundPublicId, "refundPublicId");
        amount = requirePositiveAmount(amount);
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt must not be null");
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
}
