package com.flowpay.backend.webhook.application;

public record CreatedWebhookEndpoint(WebhookEndpointSummary endpoint, String secret) {
    @Override
    public String toString() {
        return "CreatedWebhookEndpoint[endpoint=" + endpoint + ", secret=[REDACTED]]";
    }
}
