package com.flowpay.backend.payment.application;

import com.flowpay.backend.common.security.MerchantApiPrincipal;

import java.util.Objects;

public record CreatePaymentIntentCommand(
        MerchantApiPrincipal merchantContext,
        long amountMinor,
        String currency,
        String orderId,
        String description
) {

    public CreatePaymentIntentCommand {
        Objects.requireNonNull(merchantContext, "merchantContext must not be null");
    }
}
