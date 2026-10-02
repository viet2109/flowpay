package com.flowpay.backend.webhook.application;

import com.flowpay.backend.webhook.domain.WebhookEndpointStatus;
import java.time.Instant;
import java.util.List;

public record WebhookEndpointSummary(
        String publicId, String url, WebhookEndpointStatus status, List<String> events,
        Instant createdAt, Instant updatedAt
) {
}
