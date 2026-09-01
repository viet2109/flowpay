package com.flowpay.backend.refund.application;

import java.util.Objects;

public record IdempotentRefundResult(
        RefundResponseSnapshot response,
        int httpStatus,
        boolean replayed
) {

    public IdempotentRefundResult {
        response = Objects.requireNonNull(response, "response must not be null");
        if (httpStatus != response.httpStatus()) {
            throw new IllegalArgumentException(
                    "Refund httpStatus must match the response state"
            );
        }
    }
}
