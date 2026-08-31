package com.flowpay.backend.payment.application;

import com.flowpay.backend.payment.domain.PaymentStatus;
import com.flowpay.backend.payment.domain.PaymentTransactionStatus;

public record FinalizedPaymentConfirmation(
        String paymentPublicId,
        PaymentStatus paymentStatus,
        String transactionPublicId,
        PaymentTransactionStatus transactionStatus,
        String provider,
        String providerTransactionId,
        String failureCode,
        String failureMessage
) {
}
