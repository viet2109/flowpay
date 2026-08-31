package com.flowpay.backend.idempotency.application;

public enum IdempotencyAcquisitionDecision {
    NEW,
    REPLAY,
    IN_PROGRESS,
    KEY_REUSED
}
