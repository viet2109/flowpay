package com.flowpay.backend.merchant.api;

import com.flowpay.backend.common.api.ApiResponse;
import com.flowpay.backend.common.security.DashboardPrincipal;
import com.flowpay.backend.merchant.application.ApiKeyManagementUseCase;
import com.flowpay.backend.merchant.application.CreatedApiKey;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/merchant/api-keys")
@RequiredArgsConstructor
public class ApiKeyManagementController {

    private final ApiKeyManagementUseCase apiKeyManagementUseCase;
    private final ApiKeyManagementApiMapper mapper;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CreateApiKeyResponse> create(
            @AuthenticationPrincipal DashboardPrincipal principal,
            @Valid @RequestBody CreateApiKeyRequest request
    ) {
        CreatedApiKey created = apiKeyManagementUseCase.create(mapper.toCommand(principal, request));
        return ApiResponse.of(mapper.toResponse(created));
    }

    @GetMapping
    public ApiResponse<List<ApiKeyResponse>> list(
            @AuthenticationPrincipal DashboardPrincipal principal
    ) {
        return ApiResponse.of(mapper.toResponses(
                apiKeyManagementUseCase.list(principal.merchantPublicId())
        ));
    }

    @DeleteMapping("/{keyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(
            @AuthenticationPrincipal DashboardPrincipal principal,
            @PathVariable String keyId
    ) {
        apiKeyManagementUseCase.revoke(mapper.toRevokeCommand(principal, keyId));
    }
}
