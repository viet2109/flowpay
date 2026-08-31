package com.flowpay.backend.payment.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import com.flowpay.backend.payment.domain.PaymentTransactionStatus;

import java.util.List;
import java.util.Optional;

interface PaymentTransactionJpaRepository extends JpaRepository<PaymentTransactionEntity, Long> {

    Optional<PaymentTransactionEntity> findByPublicId(String publicId);

    List<PaymentTransactionEntity> findAllByPaymentIntentIdOrderByAttemptNoAsc(long paymentIntentId);

    List<PaymentTransactionEntity> findAllByPaymentIntentIdAndStatusOrderByAttemptNoAsc(
            long paymentIntentId,
            PaymentTransactionStatus status
    );

    Optional<PaymentTransactionEntity> findFirstByPaymentIntentIdOrderByAttemptNoDesc(
            long paymentIntentId
    );
}
