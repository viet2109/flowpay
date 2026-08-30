package com.flowpay.backend.idempotency.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

interface IdempotencyJpaRepository extends JpaRepository<IdempotencyRecordEntity, Long> {

    Optional<IdempotencyRecordEntity> findByMerchantIdAndOperationAndIdempotencyKey(
            long merchantId,
            String operation,
            String idempotencyKey
    );
}
