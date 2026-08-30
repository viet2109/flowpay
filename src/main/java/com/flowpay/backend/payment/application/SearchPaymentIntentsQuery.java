package com.flowpay.backend.payment.application;

import com.flowpay.backend.common.security.MerchantApiPrincipal;
import com.flowpay.backend.payment.domain.PaymentStatus;

import java.time.Instant;
import java.util.Objects;

public record SearchPaymentIntentsQuery(
        MerchantApiPrincipal merchantContext,
        PaymentStatus status,
        String orderId,
        Instant createdFrom,
        Instant createdTo,
        int page,
        int size
) {

    public SearchPaymentIntentsQuery {
        Objects.requireNonNull(merchantContext, "merchantContext must not be null");
        orderId = normalizeOptionalText(orderId);
    }

    private static String normalizeOptionalText(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
