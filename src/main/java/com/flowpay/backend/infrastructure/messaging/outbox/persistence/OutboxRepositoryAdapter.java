package com.flowpay.backend.infrastructure.messaging.outbox.persistence;

import com.flowpay.backend.infrastructure.messaging.outbox.OutboxEvent;
import com.flowpay.backend.infrastructure.messaging.outbox.OutboxRepository;
import com.flowpay.backend.infrastructure.messaging.outbox.OutboxStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;

@Repository
@RequiredArgsConstructor
public class OutboxRepositoryAdapter implements OutboxRepository {

    private static final String MARK_PUBLISHED_SQL = """
            UPDATE outbox_events
            SET status = 'PUBLISHED',
                published_at = ?,
                last_error = NULL
            WHERE event_id = ?
              AND status <> 'PUBLISHED'
            """;

    private static final String RECORD_FAILURE_SQL = """
            UPDATE outbox_events
            SET status = 'FAILED',
                published_at = NULL,
                retry_count = retry_count + 1,
                available_at = ?,
                last_error = ?
            WHERE event_id = ?
              AND status <> 'PUBLISHED'
            """;

    private static final List<OutboxStatus> UNPUBLISHED_STATUSES = List.of(
            OutboxStatus.PENDING,
            OutboxStatus.FAILED
    );

    private final OutboxEventJpaRepository repository;
    private final JdbcTemplate jdbcTemplate;

    @Override
    public OutboxEvent save(OutboxEvent event) {
        OutboxEvent candidate = Objects.requireNonNull(event, "event must not be null");
        if (candidate.internalId() != null || candidate.status() != OutboxStatus.PENDING) {
            throw new IllegalArgumentException("only a new PENDING Outbox event can be saved");
        }
        return OutboxEventPersistenceMapper.toDomain(
                repository.saveAndFlush(OutboxEventPersistenceMapper.toEntity(candidate))
        );
    }

    @Override
    public List<OutboxEvent> findDueUnpublished(Instant availableAt, int limit) {
        Instant dueAt = Objects.requireNonNull(
                availableAt,
                "availableAt must not be null"
        );
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        PageRequest page = PageRequest.of(
                0,
                limit,
                Sort.by(
                        Sort.Order.asc("availableAt"),
                        Sort.Order.asc("id")
                )
        );
        return repository.findByStatusInAndAvailableAtLessThanEqual(
                UNPUBLISHED_STATUSES,
                dueAt,
                page
        ).stream().map(OutboxEventPersistenceMapper::toDomain).toList();
    }

    @Override
    public boolean markPublished(String eventId, Instant publishedAt) {
        String stableEventId = requireText(eventId, "eventId");
        Instant publicationTime = Objects.requireNonNull(
                publishedAt,
                "publishedAt must not be null"
        );
        return jdbcTemplate.update(
                MARK_PUBLISHED_SQL,
                publicationTime.atOffset(ZoneOffset.UTC),
                stableEventId
        ) == 1;
    }

    @Override
    public boolean recordFailure(
            String eventId,
            Instant nextAvailableAt,
            String lastError
    ) {
        String stableEventId = requireText(eventId, "eventId");
        Instant retryAt = Objects.requireNonNull(
                nextAvailableAt,
                "nextAvailableAt must not be null"
        );
        String safeError = requireText(lastError, "lastError");
        return jdbcTemplate.update(
                RECORD_FAILURE_SQL,
                retryAt.atOffset(ZoneOffset.UTC),
                safeError,
                stableEventId
        ) == 1;
    }

    private static String requireText(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }
}
