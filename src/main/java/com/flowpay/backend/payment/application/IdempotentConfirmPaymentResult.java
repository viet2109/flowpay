package com.flowpay.backend.payment.application;

import com.flowpay.backend.idempotency.application.IdempotencyStoredResponse;

public record IdempotentConfirmPaymentResult(
        FinalizedPaymentConfirmation confirmation,
        IdempotencyStoredResponse replayResponse,
        boolean replayed
) {

    public IdempotentConfirmPaymentResult {
        boolean hasConfirmation = confirmation != null;
        boolean hasReplayResponse = replayResponse != null;
        if (hasConfirmation == hasReplayResponse || replayed != hasReplayResponse) {
            throw new IllegalArgumentException(
                    "Result must contain exactly one original or replay response"
            );
        }
    }

    static IdempotentConfirmPaymentResult original(
            FinalizedPaymentConfirmation confirmation
    ) {
        if (confirmation == null) {
            throw new IllegalArgumentException("confirmation must not be null");
        }
        return new IdempotentConfirmPaymentResult(confirmation, null, false);
    }

    static IdempotentConfirmPaymentResult replay(IdempotencyStoredResponse response) {
        if (response == null) {
            throw new IllegalArgumentException("response must not be null");
        }
        return new IdempotentConfirmPaymentResult(null, response, true);
    }
}
