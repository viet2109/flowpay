package com.flowpay.backend.ledger.application;

import java.time.Instant;
import java.util.Objects;

public record ReverseLedgerTransactionCommand(
        String originalLedgerTransactionPublicId,
        String description,
        Instant occurredAt
) {

    public ReverseLedgerTransactionCommand {
        originalLedgerTransactionPublicId = requireOriginalPublicId(
                originalLedgerTransactionPublicId
        );
        description = requireText(description, "description");
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    }

    private static String requireOriginalPublicId(String publicId) {
        String value = requireText(publicId, "originalLedgerTransactionPublicId");
        if (!value.startsWith("ltxn_") || value.length() == "ltxn_".length()) {
            throw new IllegalArgumentException(
                    "originalLedgerTransactionPublicId must start with ltxn_ "
                            + "and contain an identifier"
            );
        }
        if (value.length() > 64) {
            throw new IllegalArgumentException(
                    "originalLedgerTransactionPublicId must not exceed 64 characters"
            );
        }
        return value;
    }

    private static String requireText(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }
}
