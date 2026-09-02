package com.flowpay.backend.payment.application.event;

import java.time.Instant;
import java.util.Currency;
import java.util.Locale;
import java.util.Objects;

public record PaymentSucceededEventV1(
        long merchantInternalId,
        String paymentPublicId,
        long amountMinor,
        String currency,
        Instant occurredAt
) {

    public static final String EVENT_TYPE = "payment.succeeded.v1";
    public static final String AGGREGATE_TYPE = "PAYMENT_INTENT";

    public PaymentSucceededEventV1 {
        if (merchantInternalId <= 0) {
            throw new IllegalArgumentException("merchantInternalId must be positive");
        }
        paymentPublicId = requireText(paymentPublicId, "paymentPublicId");
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("amountMinor must be positive");
        }
        currency = requireCurrency(currency);
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

    private static String requireCurrency(String value) {
        Objects.requireNonNull(value, "currency must not be null");
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("currency must not be blank");
        }
        return Currency.getInstance(normalized).getCurrencyCode();
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
