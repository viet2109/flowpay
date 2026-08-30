package com.flowpay.backend.merchant.api;

import com.flowpay.backend.common.security.DashboardPrincipal;
import com.flowpay.backend.merchant.application.ApiKeySummary;
import com.flowpay.backend.merchant.application.CreateApiKeyCommand;
import com.flowpay.backend.merchant.application.CreatedApiKey;
import com.flowpay.backend.merchant.application.RevokeApiKeyCommand;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

import java.util.List;

@Mapper(
        componentModel = MappingConstants.ComponentModel.SPRING,
        unmappedTargetPolicy = ReportingPolicy.ERROR
)
public interface ApiKeyManagementApiMapper {

    @Mapping(target = "merchantPublicId", source = "principal.merchantPublicId")
    @Mapping(target = "name", source = "request.name")
    CreateApiKeyCommand toCommand(DashboardPrincipal principal, CreateApiKeyRequest request);

    @Mapping(target = "id", source = "publicId")
    @Mapping(target = "key", source = "rawKey")
    @Mapping(target = "prefix", source = "keyPrefix")
    CreateApiKeyResponse toResponse(CreatedApiKey createdApiKey);

    @Mapping(target = "id", source = "publicId")
    @Mapping(target = "prefix", source = "keyPrefix")
    ApiKeyResponse toResponse(ApiKeySummary summary);

    List<ApiKeyResponse> toResponses(List<ApiKeySummary> summaries);

    @Mapping(target = "merchantPublicId", source = "principal.merchantPublicId")
    @Mapping(target = "apiKeyPublicId", source = "keyId")
    RevokeApiKeyCommand toRevokeCommand(DashboardPrincipal principal, String keyId);
}
