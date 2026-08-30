package com.flowpay.backend.merchant.infrastructure.id;

import com.flowpay.backend.merchant.application.ApiKeyPublicIdGenerator;
import com.github.f4b6a3.ulid.UlidCreator;
import org.springframework.stereotype.Component;

@Component
final class UlidApiKeyPublicIdGenerator implements ApiKeyPublicIdGenerator {

    @Override
    public String nextId() {
        return "key_" + UlidCreator.getMonotonicUlid();
    }
}
