package com.flowpay.backend.merchant.application;

import java.util.List;

public interface ApiKeyManagementUseCase {

    CreatedApiKey create(CreateApiKeyCommand command);

    List<ApiKeySummary> list(String merchantPublicId);

    void revoke(RevokeApiKeyCommand command);
}
