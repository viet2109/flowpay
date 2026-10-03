package com.flowpay.backend.webhook.infrastructure.persistence;

import com.flowpay.backend.webhook.application.WebhookEndpointRepository;
import com.flowpay.backend.webhook.domain.WebhookEndpoint;
import com.flowpay.backend.webhook.domain.WebhookEventType;
import org.springframework.jdbc.core.JdbcTemplate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class WebhookEndpointRepositoryAdapter implements WebhookEndpointRepository {
    private final WebhookEndpointJpaRepository repository;
    private final JdbcTemplate jdbc;

    @Override
    public Optional<WebhookEndpoint> findByInternalIdForShare(long internalId, boolean skipLocked) {
        var ids = jdbc.queryForList(skipLocked
                ? "SELECT id FROM webhook_endpoints WHERE id = ? FOR SHARE SKIP LOCKED"
                : "SELECT id FROM webhook_endpoints WHERE id = ? FOR SHARE", Long.class, internalId);
        return ids.isEmpty() ? Optional.empty() : repository.findById(internalId)
                .map(WebhookEndpointPersistenceMapper::toDomain);
    }

    @Override
    public Optional<WebhookEndpoint> findByPublicIdAndMerchantIdForUpdate(String publicId, long merchantId) {
        var ids = jdbc.queryForList("""
                SELECT id FROM webhook_endpoints WHERE public_id = ? AND merchant_id = ? FOR UPDATE
                """, Long.class, publicId, merchantId);
        return ids.isEmpty() ? Optional.empty() : findByPublicIdAndMerchantId(publicId, merchantId);
    }

    @Override
    public List<Long> findActiveSubscribedIdsForShare(long merchantId, WebhookEventType type) {
        return jdbc.queryForList("""
                SELECT e.id FROM webhook_endpoints e
                WHERE e.merchant_id = ? AND e.status = 'ACTIVE'
                  AND EXISTS (SELECT 1 FROM webhook_endpoint_events s
                              WHERE s.endpoint_id = e.id AND s.event_type = ?)
                ORDER BY e.id FOR SHARE OF e
                """, Long.class, merchantId, type.value());
    }

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
