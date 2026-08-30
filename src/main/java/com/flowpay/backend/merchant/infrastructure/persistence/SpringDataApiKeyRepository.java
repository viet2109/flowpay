package com.flowpay.backend.merchant.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

interface SpringDataApiKeyRepository extends JpaRepository<ApiKeyEntity, Long> {

    Optional<ApiKeyEntity> findByPublicId(String publicId);

    Optional<ApiKeyEntity> findByPublicIdAndMerchantId(String publicId, long merchantId);

    Optional<ApiKeyEntity> findByKeyPrefix(String keyPrefix);

    List<ApiKeyEntity> findAllByMerchantIdOrderByCreatedAtDescIdDesc(long merchantId);
}
