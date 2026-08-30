package com.flowpay.backend.merchant.application;

import java.util.Objects;

public record RevokeApiKeyCommand(String merchantPublicId, String apiKeyPublicId) {

    public RevokeApiKeyCommand {
        Objects.requireNonNull(merchantPublicId, "merchantPublicId must not be null");
        Objects.requireNonNull(apiKeyPublicId, "apiKeyPublicId must not be null");
    }
}
