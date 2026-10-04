package com.flowpay.backend.webhook.application;

import java.time.Instant;

public record WebhookDeliveryAttemptView(
        int attemptNo, Instant startedAt, Instant finishedAt, Integer httpStatus,
        Integer durationMs, String errorMessage
) {
}
