package com.flowpay.backend.payment.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

interface PaymentTransactionJpaRepository extends JpaRepository<PaymentTransactionEntity, Long> {

    Optional<PaymentTransactionEntity> findByPublicId(String publicId);

    List<PaymentTransactionEntity> findAllByPaymentIntentIdOrderByAttemptNoAsc(long paymentIntentId);

    Optional<PaymentTransactionEntity> findFirstByPaymentIntentIdOrderByAttemptNoDesc(
            long paymentIntentId
    );
}
