package com.flowpay.backend.idempotency.application;

import com.flowpay.backend.idempotency.domain.IdempotencyRecord;

import java.util.Objects;

public record IdempotencyAcquisitionResult(
        IdempotencyAcquisitionDecision decision,
        IdempotencyRecord record
) {

    public IdempotencyAcquisitionResult {
        Objects.requireNonNull(decision, "decision must not be null");
        Objects.requireNonNull(record, "record must not be null");

        switch (decision) {
            case NEW, IN_PROGRESS -> {
                if (!record.isProcessing()) {
                    throw new IllegalArgumentException(
                            decision + " decision requires a PROCESSING record"
                    );
                }
            }
            case REPLAY -> {
                if (!record.isCompleted()) {
                    throw new IllegalArgumentException(
                            "REPLAY decision requires a COMPLETED record"
                    );
                }
            }
            case KEY_REUSED -> {
                // Both PROCESSING and COMPLETED records may own a reused key.
            }
        }
    }
}
