package com.flowpay.backend.ledger.infrastructure.persistence;

import com.flowpay.backend.common.money.Money;
import com.flowpay.backend.ledger.domain.LedgerEntry;
import com.flowpay.backend.ledger.domain.LedgerTransaction;

import java.util.Currency;
import java.util.Map;

final class LedgerTransactionPersistenceMapper {

    private LedgerTransactionPersistenceMapper() {
    }

    static LedgerTransaction toDomain(
            LedgerTransactionEntity entity,
            Map<Long, Currency> accountCurrencies
    ) {
        return LedgerTransaction.rehydrate(
                entity.id(),
                entity.publicId(),
                entity.postingType(),
                entity.referenceType(),
                entity.referenceId(),
                Money.of(0L, entity.currency()).currency(),
                entity.description(),
                entity.occurredAt(),
                entity.createdAt(),
                entity.entries().stream()
                        .map(entry -> toDomain(entry, accountCurrencies))
                        .toList()
        );
    }

    private static LedgerEntry toDomain(
            LedgerEntryEntity entity,
            Map<Long, Currency> accountCurrencies
    ) {
        Currency accountCurrency = accountCurrencies.get(entity.ledgerAccountId());
        if (accountCurrency == null) {
            throw new IllegalStateException(
                    "Ledger account is missing for entry " + entity.id()
            );
        }
        return LedgerEntry.rehydrate(
                entity.id(),
                entity.ledgerTransaction().id(),
                entity.ledgerAccountId(),
                entity.entryNo(),
                entity.direction(),
                entity.amountMinor(),
                accountCurrency,
                entity.createdAt()
        );
    }
}
