package com.flowpay.backend.refund.infrastructure.persistence;

import com.flowpay.backend.common.money.Money;
import com.flowpay.backend.refund.domain.Refund;
import com.flowpay.backend.refund.domain.RefundReason;

final class RefundPersistenceMapper {

    private RefundPersistenceMapper() {
    }

    static RefundEntity toEntity(Refund refund) {
        return new RefundEntity(
                refund.internalId(),
                refund.publicId(),
                refund.merchantId(),
                refund.paymentIntentId(),
                refund.amount().amountMinor(),
                refund.amount().currency().getCurrencyCode(),
                refund.status(),
                refund.reason().value(),
                refund.provider(),
                refund.providerRefundId(),
                refund.failureCode(),
                refund.failureMessage(),
                refund.createdAt(),
                refund.updatedAt(),
                refund.completedAt(),
                refund.version()
        );
    }

    static Refund toDomain(RefundEntity entity) {
        return Refund.rehydrate(
                entity.id(),
                entity.publicId(),
                entity.merchantId(),
                entity.paymentIntentId(),
                Money.of(entity.amountMinor(), entity.currency()),
                entity.status(),
                RefundReason.of(entity.reason()),
                entity.provider(),
                entity.providerRefundId(),
                entity.failureCode(),
                entity.failureMessage(),
                entity.createdAt(),
                entity.updatedAt(),
                entity.completedAt(),
                entity.version()
        );
    }
}
