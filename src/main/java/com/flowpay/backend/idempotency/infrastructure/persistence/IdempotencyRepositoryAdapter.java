package com.flowpay.backend.idempotency.infrastructure.persistence;

import com.flowpay.backend.idempotency.domain.IdempotencyRecord;
import com.flowpay.backend.idempotency.domain.IdempotencyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class IdempotencyRepositoryAdapter implements IdempotencyRepository {

    private final IdempotencyJpaRepository repository;

    @Override
    public IdempotencyRecord save(IdempotencyRecord record) {
        IdempotencyRecordEntity saved = repository.saveAndFlush(
                IdempotencyPersistenceMapper.toEntity(record)
        );
        return IdempotencyPersistenceMapper.toDomain(saved);
    }

    @Override
    public Optional<IdempotencyRecord> findByScope(
            long merchantId,
            String operation,
            String idempotencyKey
    ) {
        return repository.findByMerchantIdAndOperationAndIdempotencyKey(
                merchantId,
                operation,
                idempotencyKey
        ).map(IdempotencyPersistenceMapper::toDomain);
    }
}
