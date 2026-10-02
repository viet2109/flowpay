package com.flowpay.backend.webhook.api;

import com.flowpay.backend.common.api.ApiResponse;
import com.flowpay.backend.common.security.DashboardPrincipal;
import com.flowpay.backend.webhook.application.CreatedWebhookEndpoint;
import com.flowpay.backend.webhook.application.WebhookEndpointManagementUseCase;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/v1/merchant/webhook-endpoints")
@RequiredArgsConstructor
public class WebhookEndpointController {
    private final WebhookEndpointManagementUseCase useCase;
    private final WebhookEndpointApiMapper mapper;

    @PostMapping
    public ResponseEntity<ApiResponse<CreateWebhookEndpointResponse>> create(
            @AuthenticationPrincipal DashboardPrincipal principal,
            @Valid @RequestBody CreateWebhookEndpointRequest request
    ) {
        CreatedWebhookEndpoint created = useCase.create(mapper.toCommand(principal, request));
        return ResponseEntity.created(URI.create("/api/v1/merchant/webhook-endpoints/"
                + created.endpoint().publicId())).body(ApiResponse.of(mapper.toResponse(created)));
    }

    @GetMapping
    public ApiResponse<List<WebhookEndpointResponse>> list(
            @AuthenticationPrincipal DashboardPrincipal principal
    ) {
        return ApiResponse.of(mapper.toResponses(useCase.list(principal.merchantPublicId())));
    }

    @GetMapping("/{endpointId}")
    public ApiResponse<WebhookEndpointResponse> get(
            @AuthenticationPrincipal DashboardPrincipal principal, @PathVariable String endpointId
    ) {
        return ApiResponse.of(mapper.toResponse(useCase.get(principal.merchantPublicId(), endpointId)));
    }

    @PatchMapping("/{endpointId}")
    public ApiResponse<WebhookEndpointResponse> update(
            @AuthenticationPrincipal DashboardPrincipal principal, @PathVariable String endpointId,
            @Valid @RequestBody UpdateWebhookEndpointRequest request
    ) {
        return ApiResponse.of(mapper.toResponse(useCase.update(mapper.toCommand(principal, endpointId, request))));
    }

    @DeleteMapping("/{endpointId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disable(
            @AuthenticationPrincipal DashboardPrincipal principal, @PathVariable String endpointId
    ) {
        useCase.disable(principal.merchantPublicId(), endpointId);
    }

    @PostMapping("/{endpointId}/rotate-secret")
    public ApiResponse<RotateWebhookSecretResponse> rotateSecret(
            @AuthenticationPrincipal DashboardPrincipal principal, @PathVariable String endpointId
    ) {
        return ApiResponse.of(mapper.toResponse(useCase.rotateSecret(principal.merchantPublicId(), endpointId)));
    }
}
