package com.flowpay.backend.ledger.application;

import com.flowpay.backend.ledger.domain.LedgerBusinessReference;
import com.flowpay.backend.ledger.domain.LedgerPostingType;
import com.flowpay.backend.ledger.domain.LedgerTransaction;

import java.util.Optional;

public interface LedgerTransactionRepository {

    /**
     * Claims and inserts a complete posting within the caller's transaction.
     *
     * @return the persisted aggregate, or empty when the business reference is already claimed
     */
    Optional<LedgerTransaction> tryInsert(LedgerTransaction transaction);

    Optional<LedgerTransaction> findByPublicId(String publicId);

    Optional<LedgerTransaction> findByBusinessReference(
            LedgerPostingType postingType,
            LedgerBusinessReference businessReference
    );
}
