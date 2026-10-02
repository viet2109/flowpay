package com.flowpay.backend.webhook.infrastructure.persistence;

import com.flowpay.backend.webhook.application.WebhookEndpointRepository;
import com.flowpay.backend.webhook.domain.WebhookEndpoint;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class WebhookEndpointRepositoryAdapter implements WebhookEndpointRepository {
    private final WebhookEndpointJpaRepository repository;

    @Override
    public WebhookEndpoint save(WebhookEndpoint endpoint) {
        return WebhookEndpointPersistenceMapper.toDomain(
                repository.saveAndFlush(WebhookEndpointPersistenceMapper.toEntity(endpoint)));
    }

    @Override
    public Optional<WebhookEndpoint> findByPublicIdAndMerchantId(String publicId, long merchantId) {
        return repository.findByPublicIdAndMerchantId(publicId, merchantId)
                .map(WebhookEndpointPersistenceMapper::toDomain);
    }

    @Override
    public List<WebhookEndpoint> findAllByMerchantId(long merchantId) {
        return repository.findAllByMerchantIdOrderByCreatedAtDescIdDesc(merchantId).stream()
                .map(WebhookEndpointPersistenceMapper::toDomain).toList();
    }
}
