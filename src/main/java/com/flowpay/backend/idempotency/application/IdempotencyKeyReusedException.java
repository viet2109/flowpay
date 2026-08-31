package com.flowpay.backend.idempotency.application;

import com.flowpay.backend.common.error.ApiException;
import com.flowpay.backend.common.error.ErrorCode;
import org.springframework.http.HttpStatus;

public final class IdempotencyKeyReusedException extends ApiException {

    public IdempotencyKeyReusedException() {
        super(
                HttpStatus.CONFLICT,
                ErrorCode.IDEMPOTENCY_KEY_REUSED,
                "The idempotency key was already used with a different request."
        );
    }
}
