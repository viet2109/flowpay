package com.flowpay.backend.merchant.api;

import com.flowpay.backend.common.api.ApiResponse;
import com.flowpay.backend.common.security.DashboardPrincipal;
import com.flowpay.backend.merchant.application.MerchantProfile;
import com.flowpay.backend.merchant.application.MerchantProfileUseCase;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/merchant")
@RequiredArgsConstructor
public class MerchantProfileController {

    private final MerchantProfileUseCase merchantProfileUseCase;
    private final MerchantProfileApiMapper mapper;

    @GetMapping
    public ApiResponse<MerchantProfileResponse> get(
            @AuthenticationPrincipal DashboardPrincipal principal
    ) {
        return ApiResponse.of(mapper.toResponse(merchantProfileUseCase.get(principal.merchantPublicId())));
    }

    @PatchMapping
    public ApiResponse<MerchantProfileResponse> update(
            @AuthenticationPrincipal DashboardPrincipal principal,
            @Valid @RequestBody UpdateMerchantProfileRequest request
    ) {
        MerchantProfile profile = merchantProfileUseCase.updateName(mapper.toCommand(principal, request));
        return ApiResponse.of(mapper.toResponse(profile));
    }
}
