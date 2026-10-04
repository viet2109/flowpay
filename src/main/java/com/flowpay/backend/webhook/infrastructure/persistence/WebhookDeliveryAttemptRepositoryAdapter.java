package com.flowpay.backend.webhook.infrastructure.persistence;

import com.flowpay.backend.webhook.application.WebhookDeliveryAttemptRepository;
import com.flowpay.backend.webhook.domain.WebhookDeliveryAttempt;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class WebhookDeliveryAttemptRepositoryAdapter implements WebhookDeliveryAttemptRepository {
    private final JdbcTemplate jdbc;

    @Override
    public WebhookDeliveryAttempt insert(WebhookDeliveryAttempt attempt) {
        if (attempt.internalId() != null || attempt.finishedAt() != null) {
            throw new IllegalArgumentException("only new open attempts can be inserted");
        }
        return jdbc.queryForObject("""
                INSERT INTO webhook_delivery_attempts (delivery_id, attempt_no, started_at, created_at)
                VALUES (?, ?, ?, ?) RETURNING *
                """, this::toDomain, attempt.deliveryId(), attempt.attemptNo(),
                atUtc(attempt.startedAt()), atUtc(attempt.createdAt()));
    }

    @Override
    public WebhookDeliveryAttempt complete(WebhookDeliveryAttempt attempt) {
        if (attempt.internalId() == null || attempt.finishedAt() == null) {
            throw new IllegalArgumentException("completion requires a persisted completed attempt");
        }
        // No version column in V011: the open marker is the atomic compare-and-set guard.
        // Immutable identity/start fields are matched, never updated.
        List<WebhookDeliveryAttempt> completed = jdbc.query("""
                UPDATE webhook_delivery_attempts
                SET finished_at = ?, http_status = ?, duration_ms = ?, error_message = ?
                WHERE id = ? AND delivery_id = ? AND attempt_no = ? AND finished_at IS NULL
                    AND started_at = ? AND created_at = ?
                RETURNING *
                """, this::toDomain, atUtc(attempt.finishedAt()), attempt.httpStatus(), attempt.durationMs(),
                attempt.errorMessage(), attempt.internalId(), attempt.deliveryId(), attempt.attemptNo(),
                atUtc(attempt.startedAt()), atUtc(attempt.createdAt()));
        if (completed.isEmpty()) {
            throw new OptimisticLockingFailureException("attempt is missing, stale, or already completed");
        }
        return completed.getFirst();
    }

    @Override
    public Optional<WebhookDeliveryAttempt> findByDeliveryIdAndAttemptNo(long deliveryId, int attemptNo) {
        return jdbc.query("SELECT * FROM webhook_delivery_attempts WHERE delivery_id = ? AND attempt_no = ?",
                this::toDomain, deliveryId, attemptNo).stream().findFirst();
    }

    @Override
    public List<WebhookDeliveryAttempt> findAllByDeliveryId(long deliveryId) {
        return List.copyOf(jdbc.query("SELECT * FROM webhook_delivery_attempts WHERE delivery_id = ? ORDER BY attempt_no",
                this::toDomain, deliveryId));
    }

    private WebhookDeliveryAttempt toDomain(ResultSet row, int rowNumber) throws SQLException {
        return WebhookDeliveryAttempt.rehydrate(row.getLong("id"), row.getLong("delivery_id"),
                row.getInt("attempt_no"), instant(row, "started_at"), instant(row, "finished_at"),
                row.getObject("http_status", Integer.class), row.getObject("duration_ms", Integer.class),
                row.getString("error_message"), instant(row, "created_at"));
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime atUtc(Instant value) {
        return value.atOffset(ZoneOffset.UTC);
    }
}
