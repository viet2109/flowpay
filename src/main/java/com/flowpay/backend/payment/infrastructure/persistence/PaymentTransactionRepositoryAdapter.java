package com.flowpay.backend.payment.infrastructure.persistence;

import com.flowpay.backend.payment.application.PaymentTransactionRepository;
import com.flowpay.backend.payment.domain.PaymentTransaction;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class PaymentTransactionRepositoryAdapter implements PaymentTransactionRepository {

    private final PaymentTransactionJpaRepository repository;

    @Override
    public PaymentTransaction save(PaymentTransaction paymentTransaction) {
        PaymentTransactionEntity saved = repository.saveAndFlush(
                PaymentTransactionPersistenceMapper.toEntity(paymentTransaction)
        );
        return PaymentTransactionPersistenceMapper.toDomain(saved);
    }

    @Override
    public Optional<PaymentTransaction> findByPublicId(String publicId) {
        return repository.findByPublicId(publicId)
                .map(PaymentTransactionPersistenceMapper::toDomain);
    }

    @Override
    public List<PaymentTransaction> findByPaymentIntentId(long paymentIntentId) {
        return repository.findAllByPaymentIntentIdOrderByAttemptNoAsc(paymentIntentId).stream()
                .map(PaymentTransactionPersistenceMapper::toDomain)
                .toList();
    }

    @Override
    public Optional<PaymentTransaction> findLatestByPaymentIntentId(long paymentIntentId) {
        return repository.findFirstByPaymentIntentIdOrderByAttemptNoDesc(paymentIntentId)
                .map(PaymentTransactionPersistenceMapper::toDomain);
    }
}
