package com.flowpay.backend.payment.application;

import com.flowpay.backend.common.tenant.MerchantContext;

import java.util.Objects;

public record ConfirmPaymentCommand(
        MerchantContext merchantContext,
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
