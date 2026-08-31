package com.flowpay.backend.payment.application;

import com.flowpay.backend.common.security.MerchantApiPrincipal;
import com.flowpay.backend.idempotency.domain.IdempotencyKey;

import java.util.Objects;

public record IdempotentConfirmPaymentCommand(
        MerchantApiPrincipal merchantContext,
        IdempotencyKey idempotencyKey,
        String paymentPublicId
) {

    public IdempotentConfirmPaymentCommand {
        Objects.requireNonNull(merchantContext, "merchantContext must not be null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        Objects.requireNonNull(paymentPublicId, "paymentPublicId must not be null");
        paymentPublicId = paymentPublicId.trim();
        if (paymentPublicId.isEmpty()) {
            throw new IllegalArgumentException("paymentPublicId must not be blank");
        }
    }

    ConfirmPaymentCommand toConfirmCommand() {
        return new ConfirmPaymentCommand(merchantContext, paymentPublicId);
    }
}
