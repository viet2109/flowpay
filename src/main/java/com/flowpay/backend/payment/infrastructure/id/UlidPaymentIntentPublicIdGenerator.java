package com.flowpay.backend.payment.infrastructure.id;

import com.flowpay.backend.payment.application.PaymentIntentPublicIdGenerator;
import com.github.f4b6a3.ulid.UlidCreator;
import org.springframework.stereotype.Component;

@Component
final class UlidPaymentIntentPublicIdGenerator implements PaymentIntentPublicIdGenerator {

    @Override
    public String nextId() {
        return "pi_" + UlidCreator.getMonotonicUlid();
    }
}
