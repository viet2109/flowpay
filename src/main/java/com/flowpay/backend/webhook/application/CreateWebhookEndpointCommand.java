package com.flowpay.backend.webhook.application;

import java.util.List;

public record CreateWebhookEndpointCommand(String merchantPublicId, String url, List<String> events) {
}
