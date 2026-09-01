package com.flowpay.backend.ledger.infrastructure.persistence;

import com.flowpay.backend.common.money.Money;
import com.flowpay.backend.ledger.domain.LedgerAccount;

final class LedgerAccountPersistenceMapper {

    private LedgerAccountPersistenceMapper() {
    }

    static LedgerAccountEntity toEntity(LedgerAccount account) {
        return new LedgerAccountEntity(
                account.internalId(),
                account.publicId(),
                account.accountCode().value(),
                account.accountType(),
                account.ownerType(),
                account.ownerId(),
                account.currency().getCurrencyCode(),
                account.status(),
                account.createdAt()
        );
    }

    static LedgerAccount toDomain(LedgerAccountEntity entity) {
        return LedgerAccount.rehydrate(
                entity.id(),
                entity.publicId(),
                entity.accountCode(),
                entity.accountType(),
                entity.ownerType(),
                entity.ownerId(),
                Money.of(0L, entity.currency()).currency(),
                entity.status(),
                entity.createdAt()
        );
    }

    static LedgerAccount withInternalId(LedgerAccount account, long internalId) {
        return LedgerAccount.rehydrate(
                internalId,
                account.publicId(),
                account.accountCode().value(),
                account.accountType(),
                account.ownerType(),
                account.ownerId(),
                account.currency(),
                account.status(),
                account.createdAt()
        );
    }
}
