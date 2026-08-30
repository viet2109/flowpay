package com.flowpay.backend.payment.application;

import java.util.Objects;

public record FinalizePaymentConfirmationCommand(
        String paymentPublicId,
        String transactionPublicId,
        PaymentProviderResult providerResult
) {

    public FinalizePaymentConfirmationCommand {
        paymentPublicId = requireText(paymentPublicId, "paymentPublicId");
        transactionPublicId = requireText(transactionPublicId, "transactionPublicId");
        Objects.requireNonNull(providerResult, "providerResult must not be null");
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
