package com.flowpay.backend.refund.application;

import com.flowpay.backend.common.security.MerchantApiPrincipal;
import com.flowpay.backend.idempotency.domain.IdempotencyKey;
import com.flowpay.backend.refund.domain.RefundReason;

import java.util.Objects;

public record PrepareRefundCommand(
        MerchantApiPrincipal merchantContext,
        IdempotencyKey idempotencyKey,
        String paymentPublicId,
        long amountMinor,
        RefundReason reason
) {

    public PrepareRefundCommand {
        Objects.requireNonNull(merchantContext, "merchantContext must not be null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        Objects.requireNonNull(paymentPublicId, "paymentPublicId must not be null");
        paymentPublicId = paymentPublicId.trim();
        if (paymentPublicId.isEmpty()) {
            throw new IllegalArgumentException("paymentPublicId must not be blank");
        }
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("amountMinor must be positive");
        }
        reason = Objects.requireNonNull(reason, "reason must not be null");
    }
}
