package com.flowpay.backend.payment.infrastructure.persistence;

import com.flowpay.backend.payment.application.PaymentIntentRepository;
import com.flowpay.backend.payment.domain.PaymentIntent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class PaymentIntentRepositoryAdapter implements PaymentIntentRepository {

    private final PaymentIntentJpaRepository repository;

    @Override
    public PaymentIntent save(PaymentIntent paymentIntent) {
        PaymentIntentEntity saved = repository.saveAndFlush(
                PaymentIntentPersistenceMapper.toEntity(paymentIntent)
        );
        return PaymentIntentPersistenceMapper.toDomain(saved);
    }

    @Override
    public Optional<PaymentIntent> findByPublicIdAndMerchantId(String publicId, long merchantId) {
        return repository.findByPublicIdAndMerchantId(publicId, merchantId)
                .map(PaymentIntentPersistenceMapper::toDomain);
    }

    @Override
    public List<PaymentIntent> searchByMerchant(long merchantId) {
        return repository.findAllByMerchantIdOrderByCreatedAtDescIdDesc(merchantId).stream()
                .map(PaymentIntentPersistenceMapper::toDomain)
                .toList();
    }
}
