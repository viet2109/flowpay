package com.flowpay.backend.payment.application;

import com.flowpay.backend.common.money.Money;

public record PaymentRefundReservation(
        long paymentInternalId,
        String paymentPublicId,
        long merchantInternalId,
        Money amount,
        String provider,
        String providerTransactionId
) {

    public PaymentRefundReservation {
        if (paymentInternalId <= 0) {
            throw new IllegalArgumentException("paymentInternalId must be positive");
        }
        if (paymentPublicId == null || paymentPublicId.isBlank()) {
            throw new IllegalArgumentException("paymentPublicId must not be blank");
        }
        if (merchantInternalId <= 0) {
            throw new IllegalArgumentException("merchantInternalId must be positive");
        }
        if (amount == null || !amount.isPositive()) {
            throw new IllegalArgumentException("amount must be positive");
        }
        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("provider must not be blank");
        }
        if (providerTransactionId == null || providerTransactionId.isBlank()) {
            throw new IllegalArgumentException("providerTransactionId must not be blank");
        }
    }
}
