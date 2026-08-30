package com.flowpay.backend.merchant.api;

import com.flowpay.backend.merchant.domain.ApiKeyStatus;

import java.time.Instant;

public record ApiKeyResponse(
        String id,
        String name,
        String prefix,
        ApiKeyStatus status,
        Instant createdAt
) {
}
