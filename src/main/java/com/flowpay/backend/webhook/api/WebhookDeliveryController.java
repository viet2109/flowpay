package com.flowpay.backend.webhook.api;

import com.flowpay.backend.common.api.ApiResponse;
import com.flowpay.backend.common.api.PageMeta;
import com.flowpay.backend.common.security.DashboardPrincipal;
import com.flowpay.backend.webhook.application.WebhookDeliveryQueryService;
import com.flowpay.backend.webhook.application.WebhookDeliveryRetryService;
import com.flowpay.backend.webhook.application.WebhookDeliveryViewPage;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

@RestController
@RequestMapping("/api/v1/merchant/webhook-deliveries")
@RequiredArgsConstructor
public class WebhookDeliveryController {
    private final WebhookDeliveryQueryService queries;
    private final WebhookDeliveryRetryService retries;
    private final WebhookDeliveryApiMapper mapper;

    @GetMapping
    public ApiResponse<List<WebhookDeliveryResponse>> list(
            @AuthenticationPrincipal DashboardPrincipal principal,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String endpointId,
            @RequestParam(required = false) String eventType,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        WebhookDeliveryViewPage result = queries.list(mapper.toQuery(principal, status, endpointId, eventType, page, size));
        return new ApiResponse<>(mapper.toResponses(result.content()), new PageMeta(result.page(), result.size(),
                result.totalElements(), result.totalPages(), result.hasNext(), result.hasPrevious()));
    }

    @GetMapping("/{deliveryId}")
    public ApiResponse<WebhookDeliveryDetailResponse> get(
            @AuthenticationPrincipal DashboardPrincipal principal, @PathVariable String deliveryId
    ) {
        return ApiResponse.of(mapper.toResponse(queries.get(principal.merchantPublicId(), deliveryId)));
    }

    @PostMapping("/{deliveryId}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<WebhookDeliveryResponse> retry(
            @AuthenticationPrincipal DashboardPrincipal principal, @PathVariable String deliveryId
    ) {
        return ApiResponse.of(mapper.toResponse(retries.retry(principal.merchantPublicId(), deliveryId)));
    }
}
