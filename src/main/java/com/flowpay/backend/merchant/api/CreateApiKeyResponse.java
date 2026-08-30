package com.flowpay.backend.merchant.api;

import com.flowpay.backend.merchant.domain.ApiKeyStatus;

import java.time.Instant;

public record CreateApiKeyResponse(
        String id,
        String name,
        String key,
        String prefix,
        ApiKeyStatus status,
        Instant createdAt
) {

    @Override
    public String toString() {
        return "CreateApiKeyResponse[id=" + id
                + ", name=" + name
                + ", key=[REDACTED]"
                + ", prefix=" + prefix
                + ", status=" + status
                + ", createdAt=" + createdAt + "]";
    }
}
