package com.flowpay.backend.refund.infrastructure.id;

import com.flowpay.backend.refund.application.RefundPublicIdGenerator;
import com.github.f4b6a3.ulid.UlidCreator;
import org.springframework.stereotype.Component;

@Component
final class UlidRefundPublicIdGenerator implements RefundPublicIdGenerator {

    @Override
    public String nextId() {
        return "re_" + UlidCreator.getMonotonicUlid();
    }
}
