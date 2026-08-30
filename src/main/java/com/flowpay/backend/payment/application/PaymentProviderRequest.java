package com.flowpay.backend.payment.application;

import com.flowpay.backend.common.money.Money;

import java.util.Objects;

public record PaymentProviderRequest(
        String paymentPublicReference,
        Money amount
) {

    public PaymentProviderRequest {
        paymentPublicReference = requireText(
                paymentPublicReference,
                "paymentPublicReference"
        );
        Objects.requireNonNull(amount, "amount must not be null");
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("amount must be positive");
        }
    }

    public long amountMinor() {
        return amount.amountMinor();
    }

    public String currency() {
        return amount.currency().getCurrencyCode();
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
