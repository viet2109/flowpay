package com.flowpay.backend.merchant.infrastructure.persistence;

import com.flowpay.backend.merchant.domain.ApiKey;

final class ApiKeyPersistenceMapper {

    private ApiKeyPersistenceMapper() {
    }

    static ApiKeyEntity toEntity(ApiKey apiKey) {
        return new ApiKeyEntity(
                apiKey.id(),
                apiKey.publicId(),
                apiKey.merchantId(),
                apiKey.name(),
                apiKey.keyPrefix(),
                apiKey.keyHash(),
                apiKey.status(),
                apiKey.lastUsedAt(),
                apiKey.expiresAt(),
                apiKey.revokedAt(),
                apiKey.createdAt()
        );
    }

    static ApiKey toDomain(ApiKeyEntity entity) {
        return ApiKey.rehydrate(
                entity.id(),
                entity.publicId(),
                entity.merchantId(),
                entity.name(),
                entity.keyPrefix(),
                entity.keyHash(),
                entity.status(),
                entity.lastUsedAt(),
                entity.expiresAt(),
                entity.revokedAt(),
                entity.createdAt()
        );
    }
}
