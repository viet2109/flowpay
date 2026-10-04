package com.flowpay.backend.webhook.api;

import com.flowpay.backend.webhook.domain.WebhookEndpointStatus;
import java.time.Instant;
import java.util.List;

public record CreateWebhookEndpointResponse(
        String id, String url, WebhookEndpointStatus status, List<String> events, String secret,
        Instant createdAt, Instant updatedAt
) {
    @Override
    public String toString() {
        return "CreateWebhookEndpointResponse[id=" + id + ", secret=[REDACTED]]";
    }
}
