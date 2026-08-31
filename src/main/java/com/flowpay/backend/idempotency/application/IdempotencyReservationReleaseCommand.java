package com.flowpay.backend.idempotency.application;

import com.flowpay.backend.idempotency.domain.IdempotencyKey;
import com.flowpay.backend.idempotency.domain.IdempotencyOperation;

import java.util.Objects;

public record IdempotencyReservationReleaseCommand(
        long executionId,
        long merchantId,
        IdempotencyOperation operation,
        IdempotencyKey idempotencyKey,
        String requestHash,
        String resourceType,
        String resourcePublicId
) {

    public IdempotencyReservationReleaseCommand {
        if (executionId <= 0) {
            throw new IllegalArgumentException("executionId must be positive");
        }
        if (merchantId <= 0) {
            throw new IllegalArgumentException("merchantId must be positive");
        }
        Objects.requireNonNull(operation, "operation must not be null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        requestHash = requireText(requestHash, "requestHash");
        resourceType = requireText(resourceType, "resourceType");
        resourcePublicId = requireText(resourcePublicId, "resourcePublicId");
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
