package com.flowpay.backend.webhook.api;

import com.flowpay.backend.common.security.DashboardPrincipal;
import com.flowpay.backend.webhook.application.CreateWebhookEndpointCommand;
import com.flowpay.backend.webhook.application.CreatedWebhookEndpoint;
import com.flowpay.backend.webhook.application.RotatedWebhookSecret;
import com.flowpay.backend.webhook.application.UpdateWebhookEndpointCommand;
import com.flowpay.backend.webhook.application.WebhookEndpointSummary;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

import java.util.List;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface WebhookEndpointApiMapper {
    @Mapping(target = "merchantPublicId", source = "principal.merchantPublicId")
    CreateWebhookEndpointCommand toCommand(DashboardPrincipal principal, CreateWebhookEndpointRequest request);

    @Mapping(target = "merchantPublicId", source = "principal.merchantPublicId")
    @Mapping(target = "endpointPublicId", source = "endpointId")
    UpdateWebhookEndpointCommand toCommand(
            DashboardPrincipal principal, String endpointId, UpdateWebhookEndpointRequest request);

    @Mapping(target = "id", source = "publicId")
    WebhookEndpointResponse toResponse(WebhookEndpointSummary summary);

    List<WebhookEndpointResponse> toResponses(List<WebhookEndpointSummary> summaries);

    @Mapping(target = "id", source = "endpoint.publicId")
    @Mapping(target = "url", source = "endpoint.url")
    @Mapping(target = "status", source = "endpoint.status")
    @Mapping(target = "events", source = "endpoint.events")
    @Mapping(target = "createdAt", source = "endpoint.createdAt")
    @Mapping(target = "updatedAt", source = "endpoint.updatedAt")
    CreateWebhookEndpointResponse toResponse(CreatedWebhookEndpoint created);

    @Mapping(target = "id", source = "publicId")
    RotateWebhookSecretResponse toResponse(RotatedWebhookSecret rotated);
}
