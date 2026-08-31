package com.flowpay.backend.idempotency.application;

import com.flowpay.backend.common.error.ApiException;
import com.flowpay.backend.common.error.ErrorCode;
import org.springframework.http.HttpStatus;

public final class InvalidIdempotencyKeyException extends ApiException {

    public InvalidIdempotencyKeyException() {
        super(
                HttpStatus.BAD_REQUEST,
                ErrorCode.VALIDATION_ERROR,
                "The Idempotency-Key header must not exceed 255 characters."
        );
    }
}
