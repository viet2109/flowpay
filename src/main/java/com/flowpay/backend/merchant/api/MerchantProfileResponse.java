package com.flowpay.backend.merchant.api;

import com.flowpay.backend.merchant.domain.MerchantStatus;

import java.time.Instant;

public record MerchantProfileResponse(
        String id,
        String name,
        MerchantStatus status,
        Instant createdAt
) {
}
