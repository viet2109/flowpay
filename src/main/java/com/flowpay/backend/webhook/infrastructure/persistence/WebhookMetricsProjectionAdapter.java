package com.flowpay.backend.webhook.infrastructure.persistence;

import com.flowpay.backend.webhook.application.WebhookMetricsProjection;
import com.flowpay.backend.webhook.application.WebhookMetricsSnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class WebhookMetricsProjectionAdapter implements WebhookMetricsProjection {
    private final JdbcTemplate jdbc;

    @Override
    public WebhookMetricsSnapshot snapshot() {
        return jdbc.query(connection -> {
            var statement = connection.prepareStatement("""
                    SELECT COUNT(*) FILTER (WHERE status = 'PENDING') AS pending,
                           COUNT(*) FILTER (WHERE status = 'RETRYING') AS retrying,
                           COUNT(*) FILTER (WHERE status = 'DELIVERING') AS delivering,
                           COUNT(*) FILTER (WHERE status = 'DEAD') AS dead,
                           COALESCE(GREATEST(0, EXTRACT(EPOCH FROM statement_timestamp() -
                               MIN(next_attempt_at) FILTER (WHERE status IN ('PENDING', 'RETRYING')
                                   AND next_attempt_at <= statement_timestamp()))), 0) AS oldest_age
                    FROM webhook_deliveries WHERE status IN ('PENDING', 'RETRYING', 'DELIVERING', 'DEAD')
                    """);
            statement.setQueryTimeout(2);
            return statement;
        }, result -> {
            result.next();
            return new WebhookMetricsSnapshot(result.getLong("pending"), result.getLong("retrying"),
                    result.getLong("delivering"), result.getLong("dead"), result.getDouble("oldest_age"));
        });
    }
}
