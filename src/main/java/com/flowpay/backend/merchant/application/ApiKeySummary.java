package com.flowpay.backend.merchant.application;

import com.flowpay.backend.merchant.domain.ApiKeyStatus;

import java.time.Instant;
import java.util.Objects;

public record ApiKeySummary(
        String publicId,
        String name,
        String keyPrefix,
        ApiKeyStatus status,
        Instant createdAt
) {

    public ApiKeySummary {
        Objects.requireNonNull(publicId, "publicId must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(keyPrefix, "keyPrefix must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }
}
