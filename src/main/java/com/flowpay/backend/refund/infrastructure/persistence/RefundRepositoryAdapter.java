package com.flowpay.backend.refund.infrastructure.persistence;

import com.flowpay.backend.refund.application.RefundPage;
import com.flowpay.backend.refund.application.RefundRepository;
import com.flowpay.backend.refund.domain.Refund;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class RefundRepositoryAdapter implements RefundRepository {

    private final RefundJpaRepository repository;

    @Override
    public Refund save(Refund refund) {
        RefundEntity saved = repository.saveAndFlush(RefundPersistenceMapper.toEntity(refund));
        return RefundPersistenceMapper.toDomain(saved);
    }

    @Override
    public Optional<Refund> findByPublicIdAndMerchantId(String publicId, long merchantId) {
        return repository.findByPublicIdAndMerchantId(publicId, merchantId)
                .map(RefundPersistenceMapper::toDomain);
    }

    @Override
    public Optional<Refund> findByPublicIdAndMerchantIdForUpdate(
            String publicId,
            long merchantId
    ) {
        return repository.findByPublicIdAndMerchantIdForUpdate(publicId, merchantId)
                .map(RefundPersistenceMapper::toDomain);
    }

    @Override
    public RefundPage findByPaymentIntentIdAndMerchantId(
            long paymentIntentId,
            long merchantId,
            int page,
            int size
    ) {
        PageRequest pageRequest = PageRequest.of(
                page,
                size,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))
        );
        Page<RefundEntity> result = repository.findByPaymentIntentIdAndMerchantId(
                paymentIntentId,
                merchantId,
                pageRequest
        );
        return new RefundPage(
                result.getContent().stream()
                        .map(RefundPersistenceMapper::toDomain)
                        .toList(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages(),
                result.hasNext(),
                result.hasPrevious()
        );
    }
}
