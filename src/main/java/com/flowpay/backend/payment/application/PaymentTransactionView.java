package com.flowpay.backend.payment.application;

import com.flowpay.backend.payment.domain.PaymentTransactionStatus;

import java.time.Instant;

public record PaymentTransactionView(
        String publicId,
        int attemptNo,
        String provider,
        String providerTransactionId,
        PaymentTransactionStatus status,
        String failureCode,
        String failureMessage,
        Instant startedAt,
        Instant completedAt
) {
}
