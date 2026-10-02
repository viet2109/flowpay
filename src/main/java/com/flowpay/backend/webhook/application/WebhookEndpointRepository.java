package com.flowpay.backend.webhook.application;

import com.flowpay.backend.webhook.domain.WebhookEndpoint;
import java.util.List;
import java.util.Optional;

public interface WebhookEndpointRepository {
    WebhookEndpoint save(WebhookEndpoint endpoint);
    Optional<WebhookEndpoint> findByPublicIdAndMerchantId(String publicId, long merchantId);
    List<WebhookEndpoint> findAllByMerchantId(long merchantId);
}
