package com.flowpay.backend.common.security;

public record MerchantApiPrincipal(
        String merchantPublicId,
        String apiKeyPublicId
) {

    public MerchantApiPrincipal {
        merchantPublicId = requireText(merchantPublicId, "merchantPublicId");
        apiKeyPublicId = requireText(apiKeyPublicId, "apiKeyPublicId");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
