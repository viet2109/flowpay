package com.flowpay.backend.webhook.domain;

public enum WebhookDeliveryStatus {
    PENDING,
    DELIVERING,
    DELIVERED,
    RETRYING,
    DEAD
}
