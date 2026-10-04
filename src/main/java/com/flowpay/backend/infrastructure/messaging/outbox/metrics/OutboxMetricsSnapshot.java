package com.flowpay.backend.infrastructure.messaging.outbox.metrics;

public record OutboxMetricsSnapshot(long pending, long failed, double oldestUnpublishedAgeSeconds) { }
