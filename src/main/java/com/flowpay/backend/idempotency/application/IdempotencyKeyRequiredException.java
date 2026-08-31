package com.flowpay.backend.idempotency.application;

import com.flowpay.backend.common.error.ApiException;
import com.flowpay.backend.common.error.ErrorCode;
import org.springframework.http.HttpStatus;

public final class IdempotencyKeyRequiredException extends ApiException {

    public IdempotencyKeyRequiredException() {
        super(
                HttpStatus.BAD_REQUEST,
                ErrorCode.IDEMPOTENCY_KEY_REQUIRED,
                "The Idempotency-Key header is required."
        );
    }
}
