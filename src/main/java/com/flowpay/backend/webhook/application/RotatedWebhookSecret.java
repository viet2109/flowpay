package com.flowpay.backend.webhook.application;

import java.time.Instant;

public record RotatedWebhookSecret(String publicId, String secret, Instant updatedAt) {
    @Override
    public String toString() {
        return "RotatedWebhookSecret[publicId=" + publicId + ", secret=[REDACTED], updatedAt="
                + updatedAt + "]";
    }
}
