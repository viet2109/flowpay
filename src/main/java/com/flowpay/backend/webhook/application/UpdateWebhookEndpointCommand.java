package com.flowpay.backend.webhook.application;

import java.util.List;

public record UpdateWebhookEndpointCommand(
        String merchantPublicId, String endpointPublicId, String url, List<String> events
) {
}
