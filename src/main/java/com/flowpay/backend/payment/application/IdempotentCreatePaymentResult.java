package com.flowpay.backend.payment.application;

import java.util.Objects;

public record IdempotentCreatePaymentResult(
        CreatePaymentResponseSnapshot response,
        int httpStatus,
        boolean replayed
) {

    public IdempotentCreatePaymentResult {
        Objects.requireNonNull(response, "response must not be null");
        if (httpStatus != 201) {
            throw new IllegalArgumentException("create payment httpStatus must be 201");
        }
    }
}
