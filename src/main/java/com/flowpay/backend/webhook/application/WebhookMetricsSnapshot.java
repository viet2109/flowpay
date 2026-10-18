package com.flowpay.backend.webhook.application;

public record WebhookMetricsSnapshot(long pending, long retrying, long delivering, long dead, double oldestDueAgeSeconds) { }
