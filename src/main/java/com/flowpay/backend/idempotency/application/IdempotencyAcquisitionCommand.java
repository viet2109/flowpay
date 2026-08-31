package com.flowpay.backend.idempotency.application;

import com.flowpay.backend.idempotency.domain.IdempotencyKey;
import com.flowpay.backend.idempotency.domain.IdempotencyOperation;

import java.util.Objects;

public record IdempotencyAcquisitionCommand(
        long merchantId,
        IdempotencyOperation operation,
        IdempotencyKey idempotencyKey,
        String requestHash
) {

    public IdempotencyAcquisitionCommand {
        if (merchantId <= 0) {
            throw new IllegalArgumentException("merchantId must be positive");
        }
        Objects.requireNonNull(operation, "operation must not be null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        Objects.requireNonNull(requestHash, "requestHash must not be null");
    }
}
