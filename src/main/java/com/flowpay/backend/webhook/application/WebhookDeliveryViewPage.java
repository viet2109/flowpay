package com.flowpay.backend.webhook.application;

import java.util.List;

public record WebhookDeliveryViewPage(
        List<WebhookDeliveryView> content, int page, int size, long totalElements,
        int totalPages, boolean hasNext, boolean hasPrevious
) {
    public WebhookDeliveryViewPage {
        content = List.copyOf(content);
    }
}
