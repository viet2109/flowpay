package com.flowpay.backend.ledger.infrastructure.id;

import com.flowpay.backend.ledger.application.LedgerTransactionPublicIdGenerator;
import com.github.f4b6a3.ulid.UlidCreator;
import org.springframework.stereotype.Component;

@Component
final class UlidLedgerTransactionPublicIdGenerator
        implements LedgerTransactionPublicIdGenerator {

    @Override
    public String nextId() {
        return "ltxn_" + UlidCreator.getMonotonicUlid();
    }
}
