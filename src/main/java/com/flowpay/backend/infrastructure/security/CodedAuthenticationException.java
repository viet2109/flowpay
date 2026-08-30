package com.flowpay.backend.infrastructure.security;

import com.flowpay.backend.common.error.ErrorCode;
import org.springframework.security.core.AuthenticationException;

import java.util.Objects;

final class CodedAuthenticationException extends AuthenticationException {

    private final ErrorCode code;

    CodedAuthenticationException(ErrorCode code, String detail, Throwable cause) {
        super(detail, cause);
        this.code = Objects.requireNonNull(code, "code must not be null");
    }

    ErrorCode code() {
        return code;
    }
}
