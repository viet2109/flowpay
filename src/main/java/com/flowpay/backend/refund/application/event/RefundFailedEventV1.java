package com.flowpay.backend.refund.application.event;

import java.time.Instant;
import java.util.Currency;
import java.util.Locale;
import java.util.Objects;

/** Stable source facts; failure metadata is normalized by the provider port, never a raw response. */
public record RefundFailedEventV1(
        long merchantInternalId,
        String refundPublicId,
        String paymentPublicId,
        long amountMinor,
        String currency,
        String failureCode,
        String failureMessage,
        Instant occurredAt
) {
    public static final String EVENT_TYPE = "refund.failed.v1";
    public static final String AGGREGATE_TYPE = "REFUND";

    public RefundFailedEventV1 {
        if (merchantInternalId <= 0) {
            throw new IllegalArgumentException("merchantInternalId must be positive");
        }
        refundPublicId = requireText(refundPublicId, "refundPublicId");
        paymentPublicId = requireText(paymentPublicId, "paymentPublicId");
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("amountMinor must be positive");
        }
        currency = Currency.getInstance(requireText(currency, "currency").toUpperCase(Locale.ROOT))
                .getCurrencyCode();
        failureCode = requireText(failureCode, "failureCode");
        failureMessage = requireText(failureMessage, "failureMessage");
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    }

    public String eventType() {
        return EVENT_TYPE;
    }

    public String aggregateType() {
        return AGGREGATE_TYPE;
    }

    public String aggregateId() {
        return refundPublicId;
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }
}
