package com.flowpay.backend.webhook.application;

import com.flowpay.backend.common.error.ApiException;
import com.flowpay.backend.common.error.ErrorCode;
import com.flowpay.backend.merchant.application.MerchantAccessApi;
import com.flowpay.backend.webhook.domain.WebhookDeliveryStatus;
import com.flowpay.backend.webhook.domain.WebhookEventType;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class WebhookDeliveryQueryService {
    public static final int MAX_SIZE = 100;

    private final MerchantAccessApi merchantAccess;
    private final WebhookDeliveryQueryRepository repository;

    @Transactional(readOnly = true)
    public WebhookDeliveryViewPage list(ListWebhookDeliveriesQuery query) {
        if (query.page() < 0 || query.size() < 1 || query.size() > MAX_SIZE) {
            throw invalidQuery();
        }
        WebhookDeliveryStatus status;
        WebhookEventType eventType;
        try {
            status = query.status() == null ? null : WebhookDeliveryStatus.valueOf(query.status());
            eventType = query.eventType() == null ? null : WebhookEventType.fromValue(query.eventType());
        } catch (IllegalArgumentException exception) {
            throw invalidQuery();
        }
        if (query.endpointId() != null && (!query.endpointId().startsWith("wep_")
                || query.endpointId().length() <= 4 || query.endpointId().length() > 64)) {
            throw invalidQuery();
        }
        long merchantId = merchantAccess.requireActiveMerchant(query.merchantPublicId()).internalId();
        return repository.findByMerchantId(merchantId, status, query.endpointId(), eventType,
                query.page(), query.size());
    }

    @Transactional(readOnly = true)
    public WebhookDeliveryDetail get(String merchantPublicId, String deliveryPublicId) {
        long merchantId = merchantAccess.requireActiveMerchant(merchantPublicId).internalId();
        WebhookDeliveryView delivery = repository.findByPublicIdAndMerchantId(deliveryPublicId, merchantId)
                .orElseThrow(WebhookDeliveryQueryService::notFound);
        return new WebhookDeliveryDetail(delivery, repository.findAttemptsByDeliveryId(delivery.internalId()));
    }

    static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, ErrorCode.WEBHOOK_DELIVERY_NOT_FOUND,
                "The webhook delivery was not found.");
    }

    private static ApiException invalidQuery() {
        return new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR,
                "Supply valid delivery filters, a non-negative page, and a size between 1 and 100.");
    }
}
