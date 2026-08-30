package com.flowpay.backend.payment.application;

public record PreparedPaymentConfirmation(
        String paymentPublicId,
        String transactionPublicId,
        long amountMinor,
        String currency,
        String provider
) {
}
