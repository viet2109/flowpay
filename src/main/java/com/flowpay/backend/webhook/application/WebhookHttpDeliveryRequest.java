package com.flowpay.backend.webhook.application;

import java.util.Objects;

/** Transient attempt snapshot. Never persist or log the plaintext secret, URL, or body. */
public record WebhookHttpDeliveryRequest(String url, String eventPublicId, byte[] body,
                                         String secret, long unixTimestamp) {
    public WebhookHttpDeliveryRequest {
        Objects.requireNonNull(url, "Webhook URL must not be null");
        if (url.isBlank()) throw new IllegalArgumentException("Webhook URL must not be blank");
        Objects.requireNonNull(eventPublicId, "Webhook event identity must not be null");
        if (eventPublicId.length() > 64 || !eventPublicId.matches("evt_[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("Invalid Webhook public event identity");
        }
        body = Objects.requireNonNull(body, "Webhook body must not be null").clone();
        if (body.length == 0) throw new IllegalArgumentException("Webhook body must not be empty");
        Objects.requireNonNull(secret, "Webhook secret must not be null");
        if (secret.isBlank()) throw new IllegalArgumentException("Webhook secret must not be blank");
        if (unixTimestamp < 0) throw new IllegalArgumentException("Webhook timestamp must not be negative");
    }

    @Override
    public byte[] body() { return body.clone(); }

    @Override
    public String toString() {
        return "WebhookHttpDeliveryRequest[eventPublicId=" + eventPublicId
                + ", url=[REDACTED], body=[REDACTED], secret=[REDACTED]]";
    }
}
