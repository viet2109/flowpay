package com.flowpay.backend.webhook.infrastructure.persistence;

import org.springframework.data.repository.Repository;
import java.util.Optional;

interface WebhookEventJpaRepository extends Repository<WebhookEventEntity, Long> {
    Optional<WebhookEventEntity> findById(long id);
    Optional<WebhookEventEntity> findBySourceEventId(String sourceEventId);
}
