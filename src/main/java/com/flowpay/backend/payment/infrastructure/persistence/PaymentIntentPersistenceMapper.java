package com.flowpay.backend.payment.infrastructure.persistence;

import com.flowpay.backend.common.money.Money;
import com.flowpay.backend.payment.domain.PaymentIntent;

final class PaymentIntentPersistenceMapper {

    private PaymentIntentPersistenceMapper() {
    }

    static PaymentIntentEntity toEntity(PaymentIntent paymentIntent) {
        return new PaymentIntentEntity(
                paymentIntent.internalId(),
                paymentIntent.publicId(),
                paymentIntent.merchantId(),
                paymentIntent.merchantOrderId(),
                paymentIntent.description(),
                paymentIntent.amount().amountMinor(),
                paymentIntent.amount().currency().getCurrencyCode(),
                paymentIntent.status(),
                paymentIntent.refundedAmount().amountMinor(),
                paymentIntent.refundReservedAmount().amountMinor(),
                paymentIntent.createdAt(),
                paymentIntent.updatedAt(),
                paymentIntent.version()
        );
    }

    static PaymentIntent toDomain(PaymentIntentEntity entity) {
        return PaymentIntent.rehydrate(
                entity.id(),
                entity.publicId(),
                entity.merchantId(),
                entity.merchantOrderId(),
                entity.description(),
                Money.of(entity.amountMinor(), entity.currency()),
                entity.status(),
                Money.of(entity.refundedAmountMinor(), entity.currency()),
                Money.of(entity.refundReservedMinor(), entity.currency()),
                entity.version(),
                entity.createdAt(),
                entity.updatedAt()
        );
    }
}
