package com.flowpay.backend.ledger.infrastructure.id;

import com.flowpay.backend.ledger.application.LedgerAccountPublicIdGenerator;
import com.github.f4b6a3.ulid.UlidCreator;
import org.springframework.stereotype.Component;

@Component
final class UlidLedgerAccountPublicIdGenerator implements LedgerAccountPublicIdGenerator {

    @Override
    public String nextId() {
        return "la_" + UlidCreator.getMonotonicUlid();
    }
}
