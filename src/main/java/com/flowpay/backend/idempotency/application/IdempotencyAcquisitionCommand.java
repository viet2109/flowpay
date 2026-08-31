package com.flowpay.backend.idempotency.application;

import com.flowpay.backend.idempotency.domain.IdempotencyKey;
import com.flowpay.backend.idempotency.domain.IdempotencyOperation;

import java.util.Objects;

public record IdempotencyAcquisitionCommand(
        long merchantId,
        IdempotencyOperation operation,
        IdempotencyKey idempotencyKey,
        String requestHash,
        String resourceType,
        String resourcePublicId
) {

    public IdempotencyAcquisitionCommand(
            long merchantId,
            IdempotencyOperation operation,
            IdempotencyKey idempotencyKey,
            String requestHash
    ) {
        this(merchantId, operation, idempotencyKey, requestHash, null, null);
    }

    public IdempotencyAcquisitionCommand {
        if (merchantId <= 0) {
            throw new IllegalArgumentException("merchantId must be positive");
        }
        Objects.requireNonNull(operation, "operation must not be null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        Objects.requireNonNull(requestHash, "requestHash must not be null");
        resourceType = normalizeOptionalText(resourceType, "resourceType");
        resourcePublicId = normalizeOptionalText(resourcePublicId, "resourcePublicId");
        if ((resourceType == null) != (resourcePublicId == null)) {
            throw new IllegalArgumentException(
                    "resourceType and resourcePublicId must both be present or absent"
            );
        }
    }

    public static IdempotencyAcquisitionCommand forResource(
            long merchantId,
            IdempotencyOperation operation,
            IdempotencyKey idempotencyKey,
            String requestHash,
            String resourceType,
            String resourcePublicId
    ) {
        return new IdempotencyAcquisitionCommand(
                merchantId,
                operation,
                idempotencyKey,
                requestHash,
                resourceType,
                resourcePublicId
        );
    }

    public boolean hasResource() {
        return resourceType != null;
    }

    private static String normalizeOptionalText(String value, String fieldName) {
        if (value == null) {
            return null;
        }
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
