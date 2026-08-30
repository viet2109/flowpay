package com.flowpay.backend.payment.infrastructure.persistence;

import com.flowpay.backend.payment.domain.PaymentTransaction;

import java.time.Instant;
import java.util.Objects;

final class PaymentTransactionPersistenceMapper {

    private PaymentTransactionPersistenceMapper() {
    }

    static PaymentTransactionEntity toEntity(PaymentTransaction paymentTransaction) {
        Instant createdAt = Objects.requireNonNull(
                paymentTransaction.startedAt(),
                "a persisted payment transaction must have startedAt"
        );
        Instant updatedAt = paymentTransaction.completedAt() == null
                ? createdAt
                : paymentTransaction.completedAt();
        return new PaymentTransactionEntity(
                paymentTransaction.internalId(),
                paymentTransaction.publicId(),
                paymentTransaction.paymentIntentId(),
                paymentTransaction.attemptNo(),
                paymentTransaction.provider(),
                paymentTransaction.providerTransactionId(),
                paymentTransaction.status(),
                paymentTransaction.failureCode(),
                paymentTransaction.failureMessage(),
                paymentTransaction.startedAt(),
                paymentTransaction.completedAt(),
                createdAt,
                updatedAt,
                paymentTransaction.version()
        );
    }

    static PaymentTransaction toDomain(PaymentTransactionEntity entity) {
        return PaymentTransaction.rehydrate(
                entity.id(),
                entity.publicId(),
                entity.paymentIntentId(),
                entity.attemptNo(),
                entity.provider(),
                entity.providerTransactionId(),
                entity.status(),
                entity.failureCode(),
                entity.failureMessage(),
                entity.startedAt(),
                entity.completedAt(),
                entity.version()
        );
    }
}
