package com.flowpay.backend.ledger.domain;

import java.time.Instant;
import java.util.Currency;
import java.util.Objects;

public final class LedgerEntry {

    private final Long internalId;
    private final Long ledgerTransactionId;
    private final long ledgerAccountId;
    private final int entryNo;
    private final LedgerEntryDirection direction;
    private final long amountMinor;
    private final Currency accountCurrency;
    private final Instant createdAt;

    private LedgerEntry(
            Long internalId,
            Long ledgerTransactionId,
            long ledgerAccountId,
            int entryNo,
            LedgerEntryDirection direction,
            long amountMinor,
            Currency accountCurrency,
            Instant createdAt
    ) {
        this.internalId = validateOptionalId(internalId, "internalId");
        this.ledgerTransactionId = validateOptionalId(
                ledgerTransactionId,
                "ledgerTransactionId"
        );
        this.ledgerAccountId = validateRequiredId(ledgerAccountId, "ledgerAccountId");
        if (entryNo <= 0) {
            throw new IllegalArgumentException("entryNo must be positive");
        }
        this.entryNo = entryNo;
        this.direction = Objects.requireNonNull(direction, "direction must not be null");
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("amountMinor must be positive");
        }
        this.amountMinor = amountMinor;
        this.accountCurrency = Objects.requireNonNull(
                accountCurrency,
                "accountCurrency must not be null"
        );
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    static LedgerEntry post(
            int entryNo,
            LedgerEntryDraft draft,
            Instant createdAt
    ) {
        LedgerEntryDraft value = Objects.requireNonNull(draft, "draft must not be null");
        Long accountId = value.account().internalId();
        if (accountId == null) {
            throw new IllegalArgumentException(
                    "ledger account must be persisted before it can receive an entry"
            );
        }
        return new LedgerEntry(
                null,
                null,
                accountId,
                entryNo,
                value.direction(),
                value.amount().amountMinor(),
                value.account().currency(),
                createdAt
        );
    }

    public static LedgerEntry rehydrate(
            long internalId,
            long ledgerTransactionId,
            long ledgerAccountId,
            int entryNo,
            LedgerEntryDirection direction,
            long amountMinor,
            Currency accountCurrency,
            Instant createdAt
    ) {
        return new LedgerEntry(
                internalId,
                ledgerTransactionId,
                ledgerAccountId,
                entryNo,
                direction,
                amountMinor,
                accountCurrency,
                createdAt
        );
    }

    LedgerEntry reverse(Instant reversalCreatedAt) {
        return new LedgerEntry(
                null,
                null,
                ledgerAccountId,
                entryNo,
                direction.opposite(),
                amountMinor,
                accountCurrency,
                Objects.requireNonNull(
                        reversalCreatedAt,
                        "reversalCreatedAt must not be null"
                )
        );
    }

    private static Long validateOptionalId(Long value, String fieldName) {
        if (value != null && value <= 0) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
        return value;
    }

    private static long validateRequiredId(long value, String fieldName) {
        if (value <= 0) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
        return value;
    }

    public Long internalId() {
        return internalId;
    }

    public Long ledgerTransactionId() {
        return ledgerTransactionId;
    }

    public long ledgerAccountId() {
        return ledgerAccountId;
    }

    public int entryNo() {
        return entryNo;
    }

    public LedgerEntryDirection direction() {
        return direction;
    }

    public long amountMinor() {
        return amountMinor;
    }

    public Currency accountCurrency() {
        return accountCurrency;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
