package com.flowpay.backend.payment.application;

import com.flowpay.backend.payment.domain.PaymentStatus;

import java.util.Objects;

record PaymentConfirmationPreflight(
        long merchantId,
        String paymentPublicId,
        PaymentStatus paymentStatus
) {

    PaymentConfirmationPreflight {
        if (merchantId <= 0) {
            throw new IllegalArgumentException("merchantId must be positive");
        }
        if (paymentPublicId == null || paymentPublicId.isBlank()) {
            throw new IllegalArgumentException("paymentPublicId must not be blank");
        }
        Objects.requireNonNull(paymentStatus, "paymentStatus must not be null");
    }

    boolean isConfirmable() {
        return paymentStatus == PaymentStatus.CREATED;
    }
}
