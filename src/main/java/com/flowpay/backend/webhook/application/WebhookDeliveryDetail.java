package com.flowpay.backend.webhook.application;

import java.util.List;

public record WebhookDeliveryDetail(WebhookDeliveryView delivery, List<WebhookDeliveryAttemptView> attempts) {
    public WebhookDeliveryDetail {
        attempts = List.copyOf(attempts);
    }
}
