package com.flowpay.backend.payment.application;

import com.flowpay.backend.common.security.MerchantApiPrincipal;

import java.util.Objects;

public record ConfirmPaymentCommand(
        MerchantApiPrincipal merchantContext,
        String paymentPublicId
) {

    public ConfirmPaymentCommand {
        Objects.requireNonNull(merchantContext, "merchantContext must not be null");
        Objects.requireNonNull(paymentPublicId, "paymentPublicId must not be null");
        paymentPublicId = paymentPublicId.trim();
        if (paymentPublicId.isEmpty()) {
            throw new IllegalArgumentException("paymentPublicId must not be blank");
        }
    }
}
