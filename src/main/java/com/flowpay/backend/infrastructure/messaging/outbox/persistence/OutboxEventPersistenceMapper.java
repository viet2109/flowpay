package com.flowpay.backend.infrastructure.messaging.outbox.persistence;

import com.flowpay.backend.infrastructure.messaging.outbox.OutboxEvent;

final class OutboxEventPersistenceMapper {

    private OutboxEventPersistenceMapper() {
    }

    static OutboxEventEntity toEntity(OutboxEvent event) {
        return new OutboxEventEntity(
                event.internalId(),
                event.eventId(),
                event.aggregateType(),
                event.aggregateId(),
                event.eventType(),
                event.payload(),
                event.status(),
                event.occurredAt(),
                event.availableAt(),
                event.publishedAt(),
                event.retryCount(),
                event.lastError(),
                event.createdAt()
        );
    }

    static OutboxEvent toDomain(OutboxEventEntity entity) {
        return new OutboxEvent(
                entity.id(),
                entity.eventId(),
                entity.aggregateType(),
                entity.aggregateId(),
                entity.eventType(),
                entity.payload(),
                entity.status(),
                entity.occurredAt(),
                entity.availableAt(),
                entity.publishedAt(),
                entity.retryCount(),
                entity.lastError(),
                entity.createdAt()
        );
    }
}
