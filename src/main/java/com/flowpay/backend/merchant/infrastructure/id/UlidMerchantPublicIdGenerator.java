package com.flowpay.backend.merchant.infrastructure.id;

import com.flowpay.backend.merchant.application.MerchantPublicIdGenerator;
import com.github.f4b6a3.ulid.UlidCreator;
import org.springframework.stereotype.Component;

@Component
final class UlidMerchantPublicIdGenerator implements MerchantPublicIdGenerator {

    @Override
    public String nextId() {
        return "mrc_" + UlidCreator.getMonotonicUlid();
    }
}
