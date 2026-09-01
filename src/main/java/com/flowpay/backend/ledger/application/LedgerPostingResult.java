package com.flowpay.backend.ledger.application;

import java.util.Objects;

public record LedgerPostingResult(
        LedgerPostingOutcome outcome,
        String ledgerTransactionPublicId
) {

    public LedgerPostingResult {
        outcome = Objects.requireNonNull(outcome, "outcome must not be null");
        ledgerTransactionPublicId = requireLedgerTransactionPublicId(
                ledgerTransactionPublicId
        );
    }

    private static String requireLedgerTransactionPublicId(String publicId) {
        Objects.requireNonNull(publicId, "ledgerTransactionPublicId must not be null");
        if (!publicId.startsWith("ltxn_") || publicId.length() == "ltxn_".length()) {
            throw new IllegalArgumentException(
                    "ledgerTransactionPublicId must start with ltxn_ and contain an identifier"
            );
        }
        return publicId;
    }
}
