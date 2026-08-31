package com.flowpay.backend.idempotency.application;

import com.flowpay.backend.idempotency.domain.IdempotencyKey;
import org.springframework.stereotype.Component;

@Component
public class IdempotencyKeyParser {

    public IdempotencyKey parseRequired(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            throw new IdempotencyKeyRequiredException();
        }
        if (rawValue.length() > IdempotencyKey.MAX_LENGTH) {
            throw new InvalidIdempotencyKeyException();
        }
        return IdempotencyKey.of(rawValue);
    }
}
