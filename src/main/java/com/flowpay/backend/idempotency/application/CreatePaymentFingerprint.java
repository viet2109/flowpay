package com.flowpay.backend.idempotency.application;

import java.util.Currency;
import java.util.Locale;
import java.util.Objects;

public record CreatePaymentFingerprint(
        int version,
        long amountMinor,
        String currency,
        String orderId,
        String description
) {

    public static final int CURRENT_VERSION = 1;

    public CreatePaymentFingerprint {
        if (version != CURRENT_VERSION) {
            throw new IllegalArgumentException(
                    "create payment fingerprint version must be " + CURRENT_VERSION
            );
        }
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("amountMinor must be positive");
        }
        currency = normalizeCurrency(currency);
        orderId = normalizeOptionalText(orderId);
        description = normalizeOptionalText(description);
    }

    public static CreatePaymentFingerprint version1(
            long amountMinor,
            String currency,
            String orderId,
            String description
    ) {
        return new CreatePaymentFingerprint(
                CURRENT_VERSION,
                amountMinor,
                currency,
                orderId,
                description
        );
    }

    private static String normalizeCurrency(String currency) {
        Objects.requireNonNull(currency, "currency must not be null");
        String normalized = currency.trim().toUpperCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("currency must not be blank");
        }
        return Currency.getInstance(normalized).getCurrencyCode();
    }

    private static String normalizeOptionalText(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
