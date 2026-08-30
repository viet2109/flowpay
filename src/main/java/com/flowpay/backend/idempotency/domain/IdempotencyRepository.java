package com.flowpay.backend.idempotency.domain;

import java.util.Optional;

public interface IdempotencyRepository {

    IdempotencyRecord save(IdempotencyRecord record);

    Optional<IdempotencyRecord> findByScope(
            long merchantId,
            String operation,
            String idempotencyKey
    );
}
