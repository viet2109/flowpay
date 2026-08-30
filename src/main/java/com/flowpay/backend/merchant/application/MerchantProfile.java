package com.flowpay.backend.merchant.application;

import com.flowpay.backend.merchant.domain.MerchantStatus;

import java.time.Instant;
import java.util.Objects;

public record MerchantProfile(
        String publicId,
        String name,
        MerchantStatus status,
        Instant createdAt
) {

    public MerchantProfile {
        Objects.requireNonNull(publicId, "publicId must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }
}
