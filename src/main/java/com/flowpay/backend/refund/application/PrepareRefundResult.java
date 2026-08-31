package com.flowpay.backend.refund.application;

import com.flowpay.backend.idempotency.application.IdempotencyAcquisitionDecision;
import com.flowpay.backend.idempotency.application.IdempotencyStoredResponse;

import java.util.Objects;

public record PrepareRefundResult(
        IdempotencyAcquisitionDecision decision,
        PreparedRefund prepared,
        IdempotencyStoredResponse replayResponse
) {

    public PrepareRefundResult {
        Objects.requireNonNull(decision, "decision must not be null");
        switch (decision) {
            case NEW -> {
                if (prepared == null || replayResponse != null) {
                    throw new IllegalArgumentException(
                            "NEW decision requires only a prepared Refund"
                    );
                }
            }
            case REPLAY -> {
                if (prepared != null || replayResponse == null) {
                    throw new IllegalArgumentException(
                            "REPLAY decision requires only a stored response"
                    );
                }
            }
            case IN_PROGRESS, KEY_REUSED -> {
                if (prepared != null || replayResponse != null) {
                    throw new IllegalArgumentException(
                            decision + " decision must not expose preparation state"
                    );
                }
            }
        }
    }

    static PrepareRefundResult prepared(PreparedRefund prepared) {
        return new PrepareRefundResult(
                IdempotencyAcquisitionDecision.NEW,
                Objects.requireNonNull(prepared, "prepared must not be null"),
                null
        );
    }

    static PrepareRefundResult replay(IdempotencyStoredResponse response) {
        return new PrepareRefundResult(
                IdempotencyAcquisitionDecision.REPLAY,
                null,
                Objects.requireNonNull(response, "response must not be null")
        );
    }

    static PrepareRefundResult inProgress() {
        return new PrepareRefundResult(
                IdempotencyAcquisitionDecision.IN_PROGRESS,
                null,
                null
        );
    }

    static PrepareRefundResult keyReused() {
        return new PrepareRefundResult(
                IdempotencyAcquisitionDecision.KEY_REUSED,
                null,
                null
        );
    }
}
