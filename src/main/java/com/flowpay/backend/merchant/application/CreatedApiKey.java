package com.flowpay.backend.merchant.application;

import com.flowpay.backend.merchant.domain.ApiKeyStatus;

import java.time.Instant;
import java.util.Objects;

public record CreatedApiKey(
        String publicId,
        String name,
        String rawKey,
        String keyPrefix,
        ApiKeyStatus status,
        Instant createdAt
) {

    private static final String REDACTED = "[REDACTED]";

    public CreatedApiKey {
        Objects.requireNonNull(publicId, "publicId must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(rawKey, "rawKey must not be null");
        Objects.requireNonNull(keyPrefix, "keyPrefix must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    @Override
    public String toString() {
        return "CreatedApiKey[publicId=" + publicId
                + ", name=" + name
                + ", rawKey=" + REDACTED
                + ", keyPrefix=" + keyPrefix
                + ", status=" + status
                + ", createdAt=" + createdAt + "]";
    }
}
