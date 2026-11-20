package com.flowpay.backend.refund.application;

import com.flowpay.backend.common.tenant.MerchantContext;

import java.util.Objects;

public record ListRefundsQuery(
        MerchantContext merchantContext,
        String paymentPublicId,
        int page,
        int size
) {

    public ListRefundsQuery {
        merchantContext = Objects.requireNonNull(
                merchantContext,
                "merchantContext must not be null"
        );
        paymentPublicId = Objects.requireNonNull(
                paymentPublicId,
                "paymentPublicId must not be null"
        ).trim();
        if (paymentPublicId.isEmpty()) {
            throw new IllegalArgumentException("paymentPublicId must not be blank");
        }
    }
}
