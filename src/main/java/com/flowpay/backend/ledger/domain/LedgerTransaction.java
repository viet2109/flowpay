package com.flowpay.backend.ledger.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class LedgerTransaction {

    private static final String PUBLIC_ID_PREFIX = "ltxn_";
    private static final int PUBLIC_ID_MAX_LENGTH = 64;

    private final Long internalId;
    private final String publicId;
    private final LedgerPostingType postingType;
    private final LedgerBusinessReference businessReference;
    private final Currency currency;
    private final String description;
    private final Instant occurredAt;
    private final Instant createdAt;
    private final List<LedgerEntry> entries;

    private LedgerTransaction(
            Long internalId,
            String publicId,
            LedgerPostingType postingType,
            LedgerBusinessReference businessReference,
            Currency currency,
            String description,
            Instant occurredAt,
            Instant createdAt,
            List<LedgerEntry> entries
    ) {
        this.internalId = validateInternalId(internalId);
        this.publicId = validatePublicId(publicId);
        this.postingType = Objects.requireNonNull(postingType, "postingType must not be null");
        this.businessReference = Objects.requireNonNull(
                businessReference,
                "businessReference must not be null"
        );
        validateReference(postingType, businessReference);
        this.currency = Objects.requireNonNull(currency, "currency must not be null");
        this.description = normalizeOptionalText(description);
        this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.entries = validateAndCopyEntries(internalId, currency, entries);
    }

    public static LedgerTransaction post(
            String publicId,
            LedgerPostingType postingType,
            LedgerBusinessReference businessReference,
            Currency currency,
            String description,
            Instant occurredAt,
            Instant createdAt,
            List<LedgerEntryDraft> entryDrafts
    ) {
        Currency transactionCurrency = Objects.requireNonNull(
                currency,
                "currency must not be null"
        );
        Instant postingCreatedAt = Objects.requireNonNull(
                createdAt,
                "createdAt must not be null"
        );
        List<LedgerEntryDraft> drafts = List.copyOf(
                Objects.requireNonNull(entryDrafts, "entryDrafts must not be null")
        );
        List<LedgerEntry> postedEntries = new ArrayList<>(drafts.size());
        for (int index = 0; index < drafts.size(); index++) {
            LedgerEntryDraft draft = drafts.get(index);
            validateDraft(transactionCurrency, draft);
            postedEntries.add(LedgerEntry.post(index + 1, draft, postingCreatedAt));
        }
        return new LedgerTransaction(
                null,
                publicId,
                postingType,
                businessReference,
                transactionCurrency,
                description,
                occurredAt,
                postingCreatedAt,
                postedEntries
        );
    }

    public static LedgerTransaction rehydrate(
            long internalId,
            String publicId,
            LedgerPostingType postingType,
            LedgerReferenceType referenceType,
            String referenceId,
            Currency currency,
            String description,
            Instant occurredAt,
            Instant createdAt,
            List<LedgerEntry> entries
    ) {
        return new LedgerTransaction(
                internalId,
                publicId,
                postingType,
                LedgerBusinessReference.rehydrate(referenceType, referenceId),
                currency,
                description,
                occurredAt,
                createdAt,
                entries
        );
    }

    private static void validateDraft(Currency currency, LedgerEntryDraft draft) {
        LedgerEntryDraft value = Objects.requireNonNull(draft, "entryDraft must not be null");
        LedgerAccount account = value.account();
        if (account.status() != LedgerAccountStatus.ACTIVE) {
            throw new IllegalArgumentException("entries require ACTIVE ledger accounts");
        }
        if (!currency.equals(account.currency())) {
            throw new IllegalArgumentException(
                    "ledger account currency must match transaction currency"
            );
        }
        if (!currency.equals(value.amount().currency())) {
            throw new IllegalArgumentException("entry amount currency must match transaction currency");
        }
    }

    private static List<LedgerEntry> validateAndCopyEntries(
            Long internalId,
            Currency currency,
            List<LedgerEntry> entries
    ) {
        List<LedgerEntry> values = List.copyOf(
                Objects.requireNonNull(entries, "entries must not be null")
        );
        if (values.isEmpty()) {
            throw new IllegalArgumentException("ledger transaction must contain entries");
        }

        long debitTotal = 0L;
        long creditTotal = 0L;
        boolean hasDebit = false;
        boolean hasCredit = false;
        Set<Integer> entryNumbers = new HashSet<>();

        for (int index = 0; index < values.size(); index++) {
            LedgerEntry entry = Objects.requireNonNull(values.get(index), "entry must not be null");
            int expectedEntryNo = index + 1;
            if (!entryNumbers.add(entry.entryNo())) {
                throw new IllegalArgumentException("entry numbers must be unique");
            }
            if (entry.entryNo() != expectedEntryNo) {
                throw new IllegalArgumentException(
                        "entry numbers must be deterministic and contiguous from 1"
                );
            }
            if (!currency.equals(entry.accountCurrency())) {
                throw new IllegalArgumentException(
                        "ledger account currency must match transaction currency"
                );
            }
            validateEntryTransactionOwnership(internalId, entry);
            try {
                if (entry.direction() == LedgerEntryDirection.DEBIT) {
                    debitTotal = Math.addExact(debitTotal, entry.amountMinor());
                    hasDebit = true;
                } else {
                    creditTotal = Math.addExact(creditTotal, entry.amountMinor());
                    hasCredit = true;
                }
            } catch (ArithmeticException exception) {
                throw new IllegalArgumentException("ledger entry totals overflow", exception);
            }
        }

        if (!hasDebit || !hasCredit) {
            throw new IllegalArgumentException(
                    "ledger transaction requires at least one DEBIT and one CREDIT entry"
            );
        }
        if (debitTotal != creditTotal) {
            throw new IllegalArgumentException("ledger transaction entries must balance");
        }
        return values;
    }

    private static void validateEntryTransactionOwnership(
            Long internalId,
            LedgerEntry entry
    ) {
        if (internalId == null) {
            if (entry.ledgerTransactionId() != null) {
                throw new IllegalArgumentException(
                        "new ledger entries must not have a ledgerTransactionId"
                );
            }
            return;
        }
        if (!internalId.equals(entry.ledgerTransactionId())) {
            throw new IllegalArgumentException(
                    "entry ledgerTransactionId must match its aggregate"
            );
        }
    }

    private static void validateReference(
            LedgerPostingType postingType,
            LedgerBusinessReference reference
    ) {
        LedgerReferenceType expectedType = switch (postingType) {
            case PAYMENT_SUCCEEDED -> LedgerReferenceType.PAYMENT_INTENT;
            case REFUND_SUCCEEDED -> LedgerReferenceType.REFUND;
            case REVERSAL -> LedgerReferenceType.LEDGER_TRANSACTION;
        };
        if (reference.type() != expectedType) {
            throw new IllegalArgumentException(
                    postingType + " posting requires " + expectedType + " reference"
            );
        }
    }

    private static Long validateInternalId(Long internalId) {
        if (internalId != null && internalId <= 0) {
            throw new IllegalArgumentException("internalId must be positive");
        }
        return internalId;
    }

    private static String validatePublicId(String publicId) {
        String value = Objects.requireNonNull(publicId, "publicId must not be null");
        if (!value.startsWith(PUBLIC_ID_PREFIX) || value.length() == PUBLIC_ID_PREFIX.length()) {
            throw new IllegalArgumentException(
                    "publicId must start with ltxn_ and contain an identifier"
            );
        }
        if (value.length() > PUBLIC_ID_MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "publicId must not exceed " + PUBLIC_ID_MAX_LENGTH + " characters"
            );
        }
        return value;
    }

    private static String normalizeOptionalText(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    public Long internalId() {
        return internalId;
    }

    public String publicId() {
        return publicId;
    }

    public LedgerPostingType postingType() {
        return postingType;
    }

    public LedgerBusinessReference businessReference() {
        return businessReference;
    }

    public Currency currency() {
        return currency;
    }

    public String description() {
        return description;
    }

    public Instant occurredAt() {
        return occurredAt;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public List<LedgerEntry> entries() {
        return entries;
    }
}
