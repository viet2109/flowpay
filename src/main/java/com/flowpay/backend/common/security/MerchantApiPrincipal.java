package com.flowpay.backend.common.security;

import com.flowpay.backend.common.tenant.MerchantContext;

public record MerchantApiPrincipal(
        String merchantPublicId,
        String apiKeyPublicId
) {

    public MerchantApiPrincipal {
        merchantPublicId = requireText(merchantPublicId, "merchantPublicId");
        apiKeyPublicId = requireText(apiKeyPublicId, "apiKeyPublicId");
    }

    /** The merchant this API key acts for; use cases never see the key itself. */
    public MerchantContext merchantContext() {
        return new MerchantContext(merchantPublicId);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
