package com.flowpay.backend.idempotency.infrastructure.persistence;

import com.flowpay.backend.idempotency.domain.IdempotencyRecord;
import com.flowpay.backend.idempotency.domain.IdempotencyRepository;
import com.flowpay.backend.idempotency.domain.IdempotencyKey;
import com.flowpay.backend.idempotency.domain.IdempotencyOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.ZoneOffset;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class IdempotencyRepositoryAdapter implements IdempotencyRepository {

    private static final String INSERT_IF_ABSENT_SQL = """
            INSERT INTO idempotency_records (
                merchant_id,
                operation,
                idempotency_key,
                request_hash,
                status,
                created_at,
                expires_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (merchant_id, operation, idempotency_key) DO NOTHING
            RETURNING id
            """;

    private final IdempotencyJpaRepository repository;
    private final JdbcTemplate jdbcTemplate;

    @Override
    public IdempotencyRecord save(IdempotencyRecord record) {
        IdempotencyRecordEntity saved = repository.saveAndFlush(
                IdempotencyPersistenceMapper.toEntity(record)
        );
        return IdempotencyPersistenceMapper.toDomain(saved);
    }

    @Override
    public Optional<IdempotencyRecord> tryInsert(IdempotencyRecord record) {
        if (record.internalId() != null || !record.isProcessing()) {
            throw new IllegalArgumentException(
                    "only a new PROCESSING idempotency record can be inserted"
            );
        }

        return jdbcTemplate.query(
                INSERT_IF_ABSENT_SQL,
                (resultSet, rowNumber) -> resultSet.getLong("id"),
                record.merchantId(),
                record.operation().name(),
                record.idempotencyKey().value(),
                record.requestHash(),
                record.status().name(),
                record.createdAt().atOffset(ZoneOffset.UTC),
                record.expiresAt().atOffset(ZoneOffset.UTC)
        ).stream().findFirst().map(
                internalId -> IdempotencyPersistenceMapper.withInternalId(record, internalId)
        );
    }

    @Override
    public Optional<IdempotencyRecord> findByScope(
            long merchantId,
            IdempotencyOperation operation,
            IdempotencyKey idempotencyKey
    ) {
        return repository.findByMerchantIdAndOperationAndIdempotencyKey(
                merchantId,
                operation,
                idempotencyKey.value()
        ).map(IdempotencyPersistenceMapper::toDomain);
    }
}
