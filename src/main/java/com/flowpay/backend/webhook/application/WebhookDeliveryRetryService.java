package com.flowpay.backend.webhook.application;

import com.flowpay.backend.common.error.ApiException;
import com.flowpay.backend.common.error.ErrorCode;
import com.flowpay.backend.merchant.application.MerchantAccessApi;
import com.flowpay.backend.webhook.domain.WebhookDelivery;
import com.flowpay.backend.webhook.domain.WebhookDeliveryStatus;
import com.flowpay.backend.webhook.domain.WebhookEndpoint;
import com.flowpay.backend.webhook.domain.WebhookEndpointStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.Instant;

@Service
@RequiredArgsConstructor
public class WebhookDeliveryRetryService {
    private final MerchantAccessApi merchantAccess;
    private final WebhookDeliveryQueryRepository queries;
    private final WebhookEndpointRepository endpoints;
    private final WebhookDeliveryRepository deliveries;
    private final Clock clock;

    @Transactional
    public WebhookDeliveryView retry(String merchantPublicId, String deliveryPublicId) {
        long merchantId = merchantAccess.requireActiveMerchant(merchantPublicId).internalId();
        // JDBC projection avoids caching an unlocked JPA delivery before a concurrent retry commits.
        WebhookDeliveryView owned = queries.findByPublicIdAndMerchantId(deliveryPublicId, merchantId)
                .orElseThrow(WebhookDeliveryQueryService::notFound);
        // Same endpoint-before-delivery lock order as claim, recovery, finalization, and disable.
        WebhookEndpoint endpoint = endpoints.findByInternalIdForShare(owned.endpointInternalId(), false)
                .orElseThrow(WebhookDeliveryQueryService::notFound);
        WebhookDelivery delivery = deliveries.findByInternalIdForUpdate(owned.internalId())
                .orElseThrow(WebhookDeliveryQueryService::notFound);
        if (endpoint.status() != WebhookEndpointStatus.ACTIVE || delivery.status() != WebhookDeliveryStatus.DEAD
                || delivery.attemptCount() != owned.attemptCount()) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.WEBHOOK_INVALID_STATE,
                    "Manual retry requires a DEAD delivery with an ACTIVE endpoint.");
        }
        Instant now = clock.instant();
        delivery.retryManually(now.isBefore(delivery.updatedAt()) ? delivery.updatedAt() : now);
        deliveries.save(delivery);
        return queries.findByPublicIdAndMerchantId(deliveryPublicId, merchantId)
                .orElseThrow(WebhookDeliveryQueryService::notFound);
    }
}
