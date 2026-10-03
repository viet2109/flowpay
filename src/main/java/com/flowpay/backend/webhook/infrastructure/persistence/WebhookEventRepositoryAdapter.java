package com.flowpay.backend.webhook.infrastructure.persistence;

import com.flowpay.backend.webhook.application.WebhookEventRepository;
import com.flowpay.backend.webhook.domain.WebhookEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.time.ZoneOffset;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class WebhookEventRepositoryAdapter implements WebhookEventRepository {
    private final WebhookEventJpaRepository repository;
    private final JdbcTemplate jdbc;

    @Override
    public Optional<WebhookEvent> tryInsert(WebhookEvent event) {
        if (event.internalId() != null) {
            throw new IllegalArgumentException("only a new immutable event can be inserted");
        }
        return jdbc.query("""
                INSERT INTO webhook_events (public_id, source_event_id, merchant_id, event_type,
                    resource_type, resource_id, payload, occurred_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?)
                ON CONFLICT (source_event_id) DO NOTHING RETURNING id
                """, (rs, row) -> rs.getLong("id"), event.publicId(), event.sourceEventId(), event.merchantId(),
                event.eventType().value(), event.resourceType().name(), event.resourceId(), event.payload(),
                event.occurredAt().atOffset(ZoneOffset.UTC), event.createdAt().atOffset(ZoneOffset.UTC))
                .stream().findFirst().flatMap(this::findByInternalId);
    }

    @Override
    public Optional<WebhookEvent> findBySourceEventId(String sourceEventId) {
        return repository.findBySourceEventId(sourceEventId).map(WebhookEventPersistenceMapper::toDomain);
    }

    @Override
    public Optional<WebhookEvent> findByInternalId(long internalId) {
        return repository.findById(internalId).map(WebhookEventPersistenceMapper::toDomain);
    }
}
