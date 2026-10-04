package com.flowpay.backend.webhook.infrastructure.persistence;

import com.flowpay.backend.webhook.application.WebhookDeliveryRepository;
import com.flowpay.backend.webhook.domain.WebhookDelivery;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class WebhookDeliveryRepositoryAdapter implements WebhookDeliveryRepository {
    private final WebhookDeliveryJpaRepository repository;

    @Override
    public WebhookDelivery save(WebhookDelivery delivery) {
        return WebhookDeliveryPersistenceMapper.toDomain(
                repository.saveAndFlush(WebhookDeliveryPersistenceMapper.toEntity(delivery)));
    }

    @Override
    public Optional<WebhookDelivery> findByInternalId(long internalId) {
        return repository.findById(internalId).map(WebhookDeliveryPersistenceMapper::toDomain);
    }

    @Override
    public Optional<WebhookDelivery> findByInternalIdForUpdate(long internalId) {
        return repository.findByIdForUpdate(internalId).map(WebhookDeliveryPersistenceMapper::toDomain);
    }

    @Override
    public Optional<WebhookDelivery> findByInternalIdForUpdateSkipLocked(long internalId) {
        return repository.findByIdForUpdateSkipLocked(internalId).map(WebhookDeliveryPersistenceMapper::toDomain);
    }

    @Override
    public List<WebhookDelivery> findClaimCandidates(Instant now, int limit) {
        validateCandidates(now, limit);
        return repository.findClaimCandidates(now, limit).stream().map(WebhookDeliveryPersistenceMapper::toDomain).toList();
    }

    @Override
    public Optional<WebhookDelivery> findByPublicIdAndMerchantId(String publicId, long merchantId) {
        return repository.findOwned(publicId, merchantId).map(WebhookDeliveryPersistenceMapper::toDomain);
    }

    @Override
    public List<WebhookDelivery> findDue(Instant now, int limit) {
        validateCandidates(now, limit);
        return repository.findDue(now, limit).stream().map(WebhookDeliveryPersistenceMapper::toDomain).toList();
    }

    @Override
    public List<WebhookDelivery> findExpiredLeases(Instant now, int limit) {
        validateCandidates(now, limit);
        return repository.findExpiredLeases(now, limit).stream().map(WebhookDeliveryPersistenceMapper::toDomain).toList();
    }

    @Override
    public List<WebhookDelivery> findRecoveryCandidates(Instant now, int limit) {
        validateCandidates(now, limit);
        return repository.findRecoveryCandidates(now, limit).stream().map(WebhookDeliveryPersistenceMapper::toDomain).toList();
    }

    @Override
    public List<WebhookDelivery> findScheduledByEndpointForUpdate(long endpointId, int limit) {
        if (endpointId <= 0 || limit <= 0) throw new IllegalArgumentException("endpoint ID and limit must be positive");
        return repository.findScheduledByEndpointForUpdate(endpointId, limit).stream()
                .map(WebhookDeliveryPersistenceMapper::toDomain).toList();
    }

    private static void validateCandidates(Instant now, int limit) {
        Objects.requireNonNull(now, "now must not be null");
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
    }
}
