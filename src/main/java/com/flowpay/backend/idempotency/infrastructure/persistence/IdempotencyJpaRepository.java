package com.flowpay.backend.idempotency.infrastructure.persistence;

import com.flowpay.backend.idempotency.domain.IdempotencyOperation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

interface IdempotencyJpaRepository extends JpaRepository<IdempotencyRecordEntity, Long> {

    Optional<IdempotencyRecordEntity> findByMerchantIdAndOperationAndIdempotencyKey(
            long merchantId,
            IdempotencyOperation operation,
            String idempotencyKey
    );
}
