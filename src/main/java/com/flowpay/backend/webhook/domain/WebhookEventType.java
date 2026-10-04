package com.flowpay.backend.webhook.domain;

public enum WebhookEventType {
    PAYMENT_PROCESSING("payment.processing"),
    PAYMENT_SUCCEEDED("payment.succeeded"),
    PAYMENT_FAILED("payment.failed"),
    REFUND_PROCESSING("refund.processing"),
    REFUND_SUCCEEDED("refund.succeeded"),
    REFUND_FAILED("refund.failed");

    private final String value;

    WebhookEventType(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static WebhookEventType fromValue(String value) {
        if (value == null) {
            throw new IllegalArgumentException("event type must not be null");
        }
        return switch (value) {
            case "payment.processing" -> PAYMENT_PROCESSING;
            case "payment.succeeded" -> PAYMENT_SUCCEEDED;
            case "payment.failed" -> PAYMENT_FAILED;
            case "refund.processing" -> REFUND_PROCESSING;
            case "refund.succeeded" -> REFUND_SUCCEEDED;
            case "refund.failed" -> REFUND_FAILED;
            default -> throw new IllegalArgumentException("unsupported webhook event type");
        };
    }
}
