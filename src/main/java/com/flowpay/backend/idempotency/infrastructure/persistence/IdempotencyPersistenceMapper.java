package com.flowpay.backend.idempotency.infrastructure.persistence;

import com.flowpay.backend.idempotency.domain.IdempotencyRecord;

final class IdempotencyPersistenceMapper {

    private IdempotencyPersistenceMapper() {
    }

    static IdempotencyRecordEntity toEntity(IdempotencyRecord record) {
        return new IdempotencyRecordEntity(
                record.internalId(),
                record.merchantId(),
                record.operation(),
                record.idempotencyKey(),
                record.requestHash(),
                record.status(),
                record.resourceType(),
                record.resourcePublicId(),
                record.httpStatus(),
                record.responsePayload(),
                record.createdAt(),
                record.completedAt(),
                record.expiresAt()
        );
    }

    static IdempotencyRecord toDomain(IdempotencyRecordEntity entity) {
        return IdempotencyRecord.rehydrate(
                entity.id(),
                entity.merchantId(),
                entity.operation(),
                entity.idempotencyKey(),
                entity.requestHash(),
                entity.status(),
                entity.resourceType(),
                entity.resourcePublicId(),
                entity.httpStatus(),
                entity.responsePayload(),
                entity.createdAt(),
                entity.completedAt(),
                entity.expiresAt()
        );
    }
}
