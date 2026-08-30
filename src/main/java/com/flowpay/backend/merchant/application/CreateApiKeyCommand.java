package com.flowpay.backend.merchant.application;

import java.util.Objects;

public record CreateApiKeyCommand(String merchantPublicId, String name) {

    public CreateApiKeyCommand {
        Objects.requireNonNull(merchantPublicId, "merchantPublicId must not be null");
        Objects.requireNonNull(name, "name must not be null");
    }
}
