package com.flowpay.backend.merchant.application;

import com.flowpay.backend.merchant.domain.ApiKey;

import java.util.List;
import java.util.Optional;

public interface ApiKeyRepository {

    ApiKey save(ApiKey apiKey);

    Optional<ApiKey> findByPublicId(String publicId);

    Optional<ApiKey> findByPublicIdAndMerchantId(String publicId, long merchantId);

    Optional<ApiKey> findByKeyPrefix(String keyPrefix);

    List<ApiKey> findAllByMerchantId(long merchantId);
}
