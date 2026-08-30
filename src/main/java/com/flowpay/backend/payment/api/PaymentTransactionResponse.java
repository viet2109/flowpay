package com.flowpay.backend.payment.api;

import com.flowpay.backend.payment.domain.PaymentTransactionStatus;

import java.time.Instant;

public record PaymentTransactionResponse(
        String id,
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
