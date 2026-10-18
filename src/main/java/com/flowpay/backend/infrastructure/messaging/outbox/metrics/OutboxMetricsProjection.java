package com.flowpay.backend.infrastructure.messaging.outbox.metrics;

public interface OutboxMetricsProjection {
    OutboxMetricsSnapshot snapshot();
}
