package com.flowpay.backend.idempotency.application;

import com.flowpay.backend.common.error.ApiException;
import com.flowpay.backend.common.error.ErrorCode;
import org.springframework.http.HttpStatus;

public final class IdempotencyRequestInProgressException extends ApiException {

    public IdempotencyRequestInProgressException() {
        super(
                HttpStatus.CONFLICT,
                ErrorCode.IDEMPOTENCY_REQUEST_IN_PROGRESS,
                "A request with this idempotency key is still processing."
        );
    }
}
