package com.flowpay.backend.infrastructure.messaging.outbox.persistence;

import com.flowpay.backend.infrastructure.messaging.outbox.metrics.OutboxMetricsProjection;
import com.flowpay.backend.infrastructure.messaging.outbox.metrics.OutboxMetricsSnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class OutboxMetricsProjectionAdapter implements OutboxMetricsProjection {
    private final JdbcTemplate jdbc;

    @Override
    public OutboxMetricsSnapshot snapshot() {
        return jdbc.query(connection -> {
            var statement = connection.prepareStatement("""
                    SELECT COUNT(*) FILTER (WHERE status = 'PENDING') AS pending,
                           COUNT(*) FILTER (WHERE status = 'FAILED') AS failed,
                           COALESCE(GREATEST(0, EXTRACT(EPOCH FROM
                               statement_timestamp() - MIN(created_at))), 0) AS oldest_age
                    FROM outbox_events WHERE status IN ('PENDING', 'FAILED')
                    """);
            statement.setQueryTimeout(2);
            return statement;
        }, result -> {
            result.next();
            return new OutboxMetricsSnapshot(result.getLong("pending"), result.getLong("failed"),
                    result.getDouble("oldest_age"));
        });
    }
}
