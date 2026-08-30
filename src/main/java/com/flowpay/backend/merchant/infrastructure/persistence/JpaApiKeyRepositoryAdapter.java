package com.flowpay.backend.merchant.infrastructure.persistence;

import com.flowpay.backend.merchant.application.ApiKeyRepository;
import com.flowpay.backend.merchant.domain.ApiKey;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class JpaApiKeyRepositoryAdapter implements ApiKeyRepository {

    private final SpringDataApiKeyRepository repository;

    @Override
    public ApiKey save(ApiKey apiKey) {
        ApiKeyEntity saved = repository.saveAndFlush(ApiKeyPersistenceMapper.toEntity(apiKey));
        return ApiKeyPersistenceMapper.toDomain(saved);
    }

    @Override
    public Optional<ApiKey> findByPublicId(String publicId) {
        return repository.findByPublicId(publicId).map(ApiKeyPersistenceMapper::toDomain);
    }

    @Override
    public Optional<ApiKey> findByPublicIdAndMerchantId(String publicId, long merchantId) {
        return repository.findByPublicIdAndMerchantId(publicId, merchantId)
                .map(ApiKeyPersistenceMapper::toDomain);
    }

    @Override
    public Optional<ApiKey> findByKeyPrefix(String keyPrefix) {
        return repository.findByKeyPrefix(keyPrefix).map(ApiKeyPersistenceMapper::toDomain);
    }

    @Override
    public List<ApiKey> findAllByMerchantId(long merchantId) {
        return repository.findAllByMerchantIdOrderByCreatedAtDescIdDesc(merchantId).stream()
                .map(ApiKeyPersistenceMapper::toDomain)
                .toList();
    }
}
