package com.flowpay.backend.payment.application;

import com.flowpay.backend.common.tenant.MerchantContext;

import java.util.Objects;

public record CreatePaymentIntentCommand(
        MerchantContext merchantContext,
        long amountMinor,
        String currency,
        String orderId,
        String description
) {

    public CreatePaymentIntentCommand {
        Objects.requireNonNull(merchantContext, "merchantContext must not be null");
    }
}
