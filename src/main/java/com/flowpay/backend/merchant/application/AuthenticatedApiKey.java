package com.flowpay.backend.merchant.application;

public record AuthenticatedApiKey(
        String merchantPublicId,
        String apiKeyPublicId
) {

    public AuthenticatedApiKey {
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
