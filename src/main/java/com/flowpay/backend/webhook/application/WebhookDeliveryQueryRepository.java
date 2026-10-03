package com.flowpay.backend.webhook.application;

import com.flowpay.backend.webhook.domain.WebhookDeliveryStatus;
import com.flowpay.backend.webhook.domain.WebhookEventType;
import java.util.List;
import java.util.Optional;

public interface WebhookDeliveryQueryRepository {
    WebhookDeliveryViewPage findByMerchantId(long merchantId, WebhookDeliveryStatus status,
            String endpointPublicId, WebhookEventType eventType, int page, int size);
    Optional<WebhookDeliveryView> findByPublicIdAndMerchantId(String publicId, long merchantId);
    List<WebhookDeliveryAttemptView> findAttemptsByDeliveryId(long deliveryId);
}
