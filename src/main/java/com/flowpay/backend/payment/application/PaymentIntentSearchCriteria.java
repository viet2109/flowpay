package com.flowpay.backend.payment.application;

import com.flowpay.backend.payment.domain.PaymentStatus;

import java.time.Instant;

public record PaymentIntentSearchCriteria(
        long merchantId,
        PaymentStatus status,
        String orderId,
        Instant createdFrom,
        Instant createdTo,
        int page,
        int size
) {
}
