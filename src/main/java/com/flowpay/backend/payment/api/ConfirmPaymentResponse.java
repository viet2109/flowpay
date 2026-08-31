package com.flowpay.backend.payment.api;

import com.flowpay.backend.payment.domain.PaymentStatus;
import com.flowpay.backend.payment.domain.PaymentTransactionStatus;

public record ConfirmPaymentResponse(
        String paymentId,
        PaymentStatus paymentStatus,
        String transactionId,
        PaymentTransactionStatus transactionStatus,
        String provider,
        String providerTransactionId,
        String failureCode,
        String failureMessage
) {
}
