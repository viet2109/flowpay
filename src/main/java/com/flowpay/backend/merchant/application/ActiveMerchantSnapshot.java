package com.flowpay.backend.merchant.application;

public record ActiveMerchantSnapshot(
        Long internalId,
        String publicId
) {

    public ActiveMerchantSnapshot {
        if (internalId == null || internalId <= 0) {
            throw new IllegalArgumentException("internalId must be positive");
        }
        if (publicId == null || publicId.isBlank()) {
            throw new IllegalArgumentException("publicId must not be blank");
        }
    }
}
