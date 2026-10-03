package com.flowpay.backend.payment.application.event;

import java.time.Instant;
import java.util.Currency;
import java.util.Locale;
import java.util.Objects;

/** Stable source facts captured in the payment preparation transaction. */
public record PaymentProcessingEventV1(
        long merchantInternalId,
        String paymentPublicId,
        long amountMinor,
        String currency,
        Instant occurredAt
) {
    public static final String EVENT_TYPE = "payment.processing.v1";
    public static final String AGGREGATE_TYPE = "PAYMENT_INTENT";

    public PaymentProcessingEventV1 {
        if (merchantInternalId <= 0) {
            throw new IllegalArgumentException("merchantInternalId must be positive");
        }
        paymentPublicId = requireText(paymentPublicId, "paymentPublicId");
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("amountMinor must be positive");
        }
        currency = Currency.getInstance(requireText(currency, "currency").toUpperCase(Locale.ROOT))
                .getCurrencyCode();
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    }

    public String eventType() {
        return EVENT_TYPE;
    }

    public String aggregateType() {
        return AGGREGATE_TYPE;
    }

    public String aggregateId() {
        return paymentPublicId;
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
