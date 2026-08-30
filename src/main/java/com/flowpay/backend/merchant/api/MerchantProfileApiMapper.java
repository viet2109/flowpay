package com.flowpay.backend.merchant.api;

import com.flowpay.backend.common.security.DashboardPrincipal;
import com.flowpay.backend.merchant.application.MerchantProfile;
import com.flowpay.backend.merchant.application.UpdateMerchantProfileCommand;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

@Mapper(
        componentModel = MappingConstants.ComponentModel.SPRING,
        unmappedTargetPolicy = ReportingPolicy.ERROR
)
public interface MerchantProfileApiMapper {

    @Mapping(target = "id", source = "publicId")
    MerchantProfileResponse toResponse(MerchantProfile profile);

    @Mapping(target = "merchantPublicId", source = "principal.merchantPublicId")
    @Mapping(target = "name", source = "request.name")
    UpdateMerchantProfileCommand toCommand(
            DashboardPrincipal principal,
            UpdateMerchantProfileRequest request
    );
}
