package com.flowpay.backend.ledger.application;

import com.flowpay.backend.ledger.domain.LedgerAccount;
import com.flowpay.backend.ledger.domain.LedgerAccountCode;

import java.util.Optional;

public interface LedgerAccountRepository {

    LedgerAccount save(LedgerAccount account);

    Optional<LedgerAccount> tryInsert(LedgerAccount account);

    Optional<LedgerAccount> findByAccountCode(LedgerAccountCode accountCode);
}
