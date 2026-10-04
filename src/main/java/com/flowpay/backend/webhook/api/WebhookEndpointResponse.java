package com.flowpay.backend.webhook.api;

import com.flowpay.backend.webhook.domain.WebhookEndpointStatus;
import java.time.Instant;
import java.util.List;

public record WebhookEndpointResponse(
        String id, String url, WebhookEndpointStatus status, List<String> events,
        Instant createdAt, Instant updatedAt
) {
}
