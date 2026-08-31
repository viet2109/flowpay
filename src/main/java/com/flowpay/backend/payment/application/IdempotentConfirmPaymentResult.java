package com.flowpay.backend.payment.application;

import java.util.Objects;

public record IdempotentConfirmPaymentResult(
        ConfirmPaymentResponseSnapshot response,
        int httpStatus,
        boolean replayed
) {

    public IdempotentConfirmPaymentResult {
        Objects.requireNonNull(response, "response must not be null");
        if (httpStatus != response.httpStatus()) {
            throw new IllegalArgumentException(
                    "confirm payment httpStatus must match the response outcome"
            );
        }
    }
}
