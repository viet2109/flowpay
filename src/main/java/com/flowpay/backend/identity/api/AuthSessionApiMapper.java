package com.flowpay.backend.identity.api;

import com.flowpay.backend.identity.application.LoginCommand;
import com.flowpay.backend.identity.application.LoginResult;
import com.flowpay.backend.identity.application.RefreshResult;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

@Mapper(
        componentModel = MappingConstants.ComponentModel.SPRING,
        unmappedTargetPolicy = ReportingPolicy.ERROR
)
public interface AuthSessionApiMapper {

    LoginCommand toCommand(LoginRequest request);

    @Mapping(target = "accessToken", source = "accessToken.value")
    @Mapping(target = "expiresIn", source = "accessToken.expiresInSeconds")
    LoginResponse toResponse(LoginResult result);

    @Mapping(target = "id", source = "publicId")
    LoginResponse.UserView toUserView(LoginResult.UserSnapshot user);

    @Mapping(target = "accessToken", source = "accessToken.value")
    @Mapping(target = "expiresIn", source = "accessToken.expiresInSeconds")
    RefreshResponse toResponse(RefreshResult result);
}
