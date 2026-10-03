package com.flowpay.backend.webhook.application;

import com.flowpay.backend.webhook.domain.WebhookEndpoint;
import com.flowpay.backend.webhook.domain.WebhookEventType;
import java.util.List;
import java.util.Optional;

public interface WebhookEndpointRepository {
    WebhookEndpoint save(WebhookEndpoint endpoint);
    Optional<WebhookEndpoint> findByPublicIdAndMerchantId(String publicId, long merchantId);
    /** Caller-owned transaction; serialize disable against materialization. */
    Optional<WebhookEndpoint> findByPublicIdAndMerchantIdForUpdate(String publicId, long merchantId);
    /** Snapshot matching endpoint IDs while holding shared row locks until commit. */
    List<Long> findActiveSubscribedIdsForShare(long merchantId, WebhookEventType type);
    List<WebhookEndpoint> findAllByMerchantId(long merchantId);
}
