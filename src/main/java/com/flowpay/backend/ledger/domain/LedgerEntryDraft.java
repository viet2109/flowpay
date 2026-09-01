package com.flowpay.backend.ledger.domain;

import com.flowpay.backend.common.money.Money;

import java.util.Objects;

public record LedgerEntryDraft(
        LedgerAccount account,
        LedgerEntryDirection direction,
        Money amount
) {

    public LedgerEntryDraft {
        Objects.requireNonNull(account, "account must not be null");
        Objects.requireNonNull(direction, "direction must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("entry amount must be positive");
        }
    }

    public static LedgerEntryDraft debit(LedgerAccount account, Money amount) {
        return new LedgerEntryDraft(account, LedgerEntryDirection.DEBIT, amount);
    }

    public static LedgerEntryDraft credit(LedgerAccount account, Money amount) {
        return new LedgerEntryDraft(account, LedgerEntryDirection.CREDIT, amount);
    }
}
