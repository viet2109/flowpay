package com.flowpay.backend.idempotency.application;

import java.time.Duration;

final class IdempotencyRetention {

    static final Duration DEFAULT = Duration.ofHours(24);

    private IdempotencyRetention() {
    }
}
