package com.flowpay.backend.webhook.api;

import java.time.Instant;

public record RotateWebhookSecretResponse(String id, String secret, Instant updatedAt) {
    @Override
    public String toString() {
        return "RotateWebhookSecretResponse[id=" + id + ", secret=[REDACTED]]";
    }
}
