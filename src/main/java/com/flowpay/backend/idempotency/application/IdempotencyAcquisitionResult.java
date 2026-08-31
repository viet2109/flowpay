package com.flowpay.backend.idempotency.application;

import java.util.Objects;

public record IdempotencyAcquisitionResult(
        IdempotencyAcquisitionDecision decision,
        Long executionId,
        IdempotencyStoredResponse replayResponse
) {

    public IdempotencyAcquisitionResult {
        Objects.requireNonNull(decision, "decision must not be null");
        if (executionId != null && executionId <= 0) {
            throw new IllegalArgumentException("executionId must be positive");
        }

        switch (decision) {
            case NEW -> {
                if (executionId == null || replayResponse != null) {
                    throw new IllegalArgumentException(
                            "NEW decision requires only an executionId"
                    );
                }
            }
            case REPLAY -> {
                if (executionId != null || replayResponse == null) {
                    throw new IllegalArgumentException(
                            "REPLAY decision requires only a stored response"
                    );
                }
            }
            case IN_PROGRESS, KEY_REUSED -> {
                if (executionId != null || replayResponse != null) {
                    throw new IllegalArgumentException(
                            decision + " decision must not expose record state"
                    );
                }
            }
        }
    }

    static IdempotencyAcquisitionResult newExecution(long executionId) {
        return new IdempotencyAcquisitionResult(
                IdempotencyAcquisitionDecision.NEW,
                executionId,
                null
        );
    }

    static IdempotencyAcquisitionResult replay(IdempotencyStoredResponse response) {
        return new IdempotencyAcquisitionResult(
                IdempotencyAcquisitionDecision.REPLAY,
                null,
                response
        );
    }

    static IdempotencyAcquisitionResult inProgress() {
        return new IdempotencyAcquisitionResult(
                IdempotencyAcquisitionDecision.IN_PROGRESS,
                null,
                null
        );
    }

    static IdempotencyAcquisitionResult keyReused() {
        return new IdempotencyAcquisitionResult(
                IdempotencyAcquisitionDecision.KEY_REUSED,
                null,
                null
        );
    }
}
