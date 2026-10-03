package com.flowpay.backend.webhook.application;

/** Status and bounded diagnostics only: no response body, URL, exception text, or signature. */
public record WebhookHttpDeliveryResult(Integer httpStatus, long durationMs, Failure failure) {
    public WebhookHttpDeliveryResult {
        if (durationMs < 0) throw new IllegalArgumentException("Webhook duration must not be negative");
        if (httpStatus == null) {
            if (failure == null || failure == Failure.HTTP_STATUS) {
                throw new IllegalArgumentException("A transport failure must have a non-HTTP diagnostic");
            }
        } else {
            if (httpStatus < 100 || httpStatus > 599) throw new IllegalArgumentException("Invalid HTTP status");
            boolean success = httpStatus >= 200 && httpStatus <= 299;
            if (success ? failure != null : failure != Failure.HTTP_STATUS) {
                throw new IllegalArgumentException("HTTP status and Webhook outcome must agree");
            }
        }
    }

    public static WebhookHttpDeliveryResult http(int status, long durationMs) {
        return new WebhookHttpDeliveryResult(status, durationMs,
                status >= 200 && status <= 299 ? null : Failure.HTTP_STATUS);
    }

    public static WebhookHttpDeliveryResult failed(Failure failure, long durationMs) {
        return new WebhookHttpDeliveryResult(null, durationMs, failure);
    }

    public boolean successful() { return failure == null; }
    public String errorMessage() { return failure == null ? null : failure.name(); }

    public enum Failure {
        HTTP_STATUS, CONNECT_TIMEOUT, REQUEST_TIMEOUT, TLS_FAILURE, TRANSPORT_FAILURE, INTERRUPTED, INVALID_URL
    }
}
