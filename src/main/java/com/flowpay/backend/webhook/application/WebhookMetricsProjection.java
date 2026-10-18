package com.flowpay.backend.webhook.application;

public interface WebhookMetricsProjection {
    WebhookMetricsSnapshot snapshot();
}
