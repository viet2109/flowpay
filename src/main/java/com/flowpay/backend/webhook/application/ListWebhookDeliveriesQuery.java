package com.flowpay.backend.webhook.application;

public record ListWebhookDeliveriesQuery(
        String merchantPublicId, String status, String endpointId, String eventType, int page, int size
) {
}
