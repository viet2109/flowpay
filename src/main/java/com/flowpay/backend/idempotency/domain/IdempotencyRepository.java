package com.flowpay.backend.idempotency.domain;

import java.util.Optional;

public interface IdempotencyRepository {

    IdempotencyRecord save(IdempotencyRecord record);

    Optional<IdempotencyRecord> tryInsert(IdempotencyRecord record);

    Optional<IdempotencyRecord> findByInternalId(long internalId);

    Optional<IdempotencyRecord> findByScope(
            long merchantId,
            IdempotencyOperation operation,
            IdempotencyKey idempotencyKey
    );
}
