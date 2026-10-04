package com.flowpay.backend.webhook.domain;

public enum WebhookResourceType {
    PAYMENT_INTENT("pi_"),
    REFUND("re_");

    private final String publicIdPrefix;

    WebhookResourceType(String publicIdPrefix) {
        this.publicIdPrefix = publicIdPrefix;
    }

    public String publicIdPrefix() {
        return publicIdPrefix;
    }

    public static WebhookResourceType forEventType(WebhookEventType eventType) {
        if (eventType == null) {
            throw new IllegalArgumentException("eventType must not be null");
        }
        return switch (eventType) {
            case PAYMENT_PROCESSING, PAYMENT_SUCCEEDED, PAYMENT_FAILED -> PAYMENT_INTENT;
            case REFUND_PROCESSING, REFUND_SUCCEEDED, REFUND_FAILED -> REFUND;
        };
    }
}
