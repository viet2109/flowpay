package com.flowpay.backend.webhook.api;

import java.time.Instant;

public record WebhookDeliveryAttemptResponse(
        int attemptNo, Instant startedAt, Instant finishedAt, Integer httpStatus,
        Integer durationMs, String errorMessage
) {
}
