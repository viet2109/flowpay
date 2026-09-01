package com.flowpay.backend.ledger.domain;

public enum LedgerEntryDirection {
    DEBIT,
    CREDIT;

    public LedgerEntryDirection opposite() {
        return this == DEBIT ? CREDIT : DEBIT;
    }
}
