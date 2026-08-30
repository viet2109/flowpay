package com.flowpay.backend.merchant.api;

import com.flowpay.backend.common.api.ApiResponse;
import com.flowpay.backend.common.security.DashboardPrincipal;
import com.flowpay.backend.merchant.application.MerchantProfile;
import com.flowpay.backend.merchant.application.MerchantProfileUseCase;
import com.flowpay.backend.merchant.application.UpdateMerchantProfileCommand;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/merchant")
public class MerchantProfileController {

    private final MerchantProfileUseCase merchantProfileUseCase;

    public MerchantProfileController(MerchantProfileUseCase merchantProfileUseCase) {
        this.merchantProfileUseCase = merchantProfileUseCase;
    }

    @GetMapping
    public ApiResponse<MerchantProfileResponse> get(
            @AuthenticationPrincipal DashboardPrincipal principal
    ) {
        return ApiResponse.of(toResponse(merchantProfileUseCase.get(principal.merchantPublicId())));
    }

    @PatchMapping
    public ApiResponse<MerchantProfileResponse> update(
            @AuthenticationPrincipal DashboardPrincipal principal,
            @Valid @RequestBody UpdateMerchantProfileRequest request
    ) {
        MerchantProfile profile = merchantProfileUseCase.updateName(new UpdateMerchantProfileCommand(
                principal.merchantPublicId(),
                request.name()
        ));
        return ApiResponse.of(toResponse(profile));
    }

    private static MerchantProfileResponse toResponse(MerchantProfile profile) {
        return new MerchantProfileResponse(
                profile.publicId(),
                profile.name(),
                profile.status(),
                profile.createdAt()
        );
    }
}
