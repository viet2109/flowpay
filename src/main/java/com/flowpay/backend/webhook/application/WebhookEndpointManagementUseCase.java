package com.flowpay.backend.webhook.application;

import java.util.List;

public interface WebhookEndpointManagementUseCase {
    CreatedWebhookEndpoint create(CreateWebhookEndpointCommand command);
    List<WebhookEndpointSummary> list(String merchantPublicId);
    WebhookEndpointSummary get(String merchantPublicId, String endpointPublicId);
    WebhookEndpointSummary update(UpdateWebhookEndpointCommand command);
    void disable(String merchantPublicId, String endpointPublicId);
    RotatedWebhookSecret rotateSecret(String merchantPublicId, String endpointPublicId);
}
