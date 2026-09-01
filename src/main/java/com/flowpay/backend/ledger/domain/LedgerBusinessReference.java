package com.flowpay.backend.ledger.domain;

import java.util.Objects;

public final class LedgerBusinessReference {

    private static final int REFERENCE_ID_MAX_LENGTH = 64;

    private final LedgerReferenceType type;
    private final String referenceId;

    private LedgerBusinessReference(LedgerReferenceType type, String referenceId) {
        this.type = Objects.requireNonNull(type, "type must not be null");
        this.referenceId = validateReferenceId(type, referenceId);
    }

    public static LedgerBusinessReference paymentIntent(String paymentIntentPublicId) {
        return new LedgerBusinessReference(
                LedgerReferenceType.PAYMENT_INTENT,
                paymentIntentPublicId
        );
    }

    public static LedgerBusinessReference refund(String refundPublicId) {
        return new LedgerBusinessReference(LedgerReferenceType.REFUND, refundPublicId);
    }

    public static LedgerBusinessReference ledgerTransaction(
            String ledgerTransactionPublicId
    ) {
        return new LedgerBusinessReference(
                LedgerReferenceType.LEDGER_TRANSACTION,
                ledgerTransactionPublicId
        );
    }

    public static LedgerBusinessReference rehydrate(
            LedgerReferenceType type,
            String referenceId
    ) {
        return new LedgerBusinessReference(type, referenceId);
    }

    private static String validateReferenceId(
            LedgerReferenceType type,
            String referenceId
    ) {
        String value = Objects.requireNonNull(referenceId, "referenceId must not be null");
        String expectedPrefix = switch (type) {
            case PAYMENT_INTENT -> "pi_";
            case REFUND -> "re_";
            case LEDGER_TRANSACTION -> "ltxn_";
        };
        if (!value.startsWith(expectedPrefix) || value.length() == expectedPrefix.length()) {
            throw new IllegalArgumentException(
                    "referenceId must start with "
                            + expectedPrefix
                            + " and contain an identifier"
            );
        }
        if (value.length() > REFERENCE_ID_MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "referenceId must not exceed " + REFERENCE_ID_MAX_LENGTH + " characters"
            );
        }
        return value;
    }

    public LedgerReferenceType type() {
        return type;
    }

    public String referenceId() {
        return referenceId;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof LedgerBusinessReference that
                && type == that.type
                && referenceId.equals(that.referenceId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, referenceId);
    }

    @Override
    public String toString() {
        return type + ":" + referenceId;
    }
}
