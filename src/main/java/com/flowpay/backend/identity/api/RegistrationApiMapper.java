package com.flowpay.backend.identity.api;

import com.flowpay.backend.identity.application.RegistrationCommand;
import com.flowpay.backend.identity.application.RegistrationResult;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

@Mapper(
        componentModel = MappingConstants.ComponentModel.SPRING,
        unmappedTargetPolicy = ReportingPolicy.ERROR
)
public interface RegistrationApiMapper {

    RegistrationCommand toCommand(RegisterRequest request);

    RegisterResponse toResponse(RegistrationResult result);

    @Mapping(target = "id", source = "publicId")
    RegisterResponse.UserView toUserView(RegistrationResult.UserSnapshot user);

    @Mapping(target = "id", source = "publicId")
    RegisterResponse.MerchantView toMerchantView(RegistrationResult.MerchantSnapshot merchant);
}
