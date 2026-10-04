package com.flowpay.backend.webhook.api;

import com.flowpay.backend.common.security.DashboardPrincipal;
import com.flowpay.backend.webhook.application.ListWebhookDeliveriesQuery;
import com.flowpay.backend.webhook.application.WebhookDeliveryAttemptView;
import com.flowpay.backend.webhook.application.WebhookDeliveryDetail;
import com.flowpay.backend.webhook.application.WebhookDeliveryView;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;
import java.util.List;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface WebhookDeliveryApiMapper {
    @Mapping(target = "merchantPublicId", source = "principal.merchantPublicId")
    ListWebhookDeliveriesQuery toQuery(DashboardPrincipal principal, String status, String endpointId,
            String eventType, int page, int size);

    @Mapping(target = "id", source = "publicId")
    WebhookDeliveryResponse toResponse(WebhookDeliveryView view);

    List<WebhookDeliveryResponse> toResponses(List<WebhookDeliveryView> views);

    @Mapping(target = ".", source = "delivery")
    @Mapping(target = "id", source = "delivery.publicId")
    WebhookDeliveryDetailResponse toResponse(WebhookDeliveryDetail detail);

    WebhookDeliveryAttemptResponse toResponse(WebhookDeliveryAttemptView attempt);
}
