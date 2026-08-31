package com.flowpay.backend.payment.application;

import com.flowpay.backend.common.security.MerchantApiPrincipal;
import com.flowpay.backend.idempotency.domain.IdempotencyKey;

import java.util.Objects;

public record IdempotentCreatePaymentCommand(
        MerchantApiPrincipal merchantContext,
        IdempotencyKey idempotencyKey,
        long amountMinor,
        String currency,
        String orderId,
        String description
) {

    public IdempotentCreatePaymentCommand {
        Objects.requireNonNull(merchantContext, "merchantContext must not be null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
    }

    CreatePaymentIntentCommand toCreateCommand() {
        return new CreatePaymentIntentCommand(
                merchantContext,
                amountMinor,
                currency,
                orderId,
                description
        );
    }
}
