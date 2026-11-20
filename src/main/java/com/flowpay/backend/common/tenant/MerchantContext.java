package com.flowpay.backend.common.tenant;

/**
 * The merchant a request acts for, as established by authentication: a merchant
 * API key or a dashboard session. Only the api layer builds it, from an
 * authenticated principal, so use cases never take a client-controlled
 * merchant ID. It carries the tenant scope only, not who authenticated.
 */
public record MerchantContext(String merchantPublicId) {

    public MerchantContext {
        if (merchantPublicId == null || merchantPublicId.isBlank()) {
            throw new IllegalArgumentException("merchantPublicId must not be blank");
        }
    }
}
