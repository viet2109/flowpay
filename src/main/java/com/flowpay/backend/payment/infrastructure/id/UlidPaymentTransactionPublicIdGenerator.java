package com.flowpay.backend.payment.infrastructure.id;

import com.flowpay.backend.payment.application.PaymentTransactionPublicIdGenerator;
import com.github.f4b6a3.ulid.UlidCreator;
import org.springframework.stereotype.Component;

@Component
final class UlidPaymentTransactionPublicIdGenerator
        implements PaymentTransactionPublicIdGenerator {

    @Override
    public String nextId() {
        return "ptxn_" + UlidCreator.getMonotonicUlid();
    }
}
