package com.flowpay.backend.webhook.infrastructure.persistence;

import com.flowpay.backend.common.observability.AsyncWorkTracing;
import com.flowpay.backend.webhook.application.WebhookWorkerTracing;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/** Webhook-owned lookup outside claim/finalize transactions; no network call holds a DB connection. */
@Component
@RequiredArgsConstructor
public class DurableWebhookWorkerTracing implements WebhookWorkerTracing {
    private final JdbcTemplate jdbc;
    private final AsyncWorkTracing tracing;

    @Override public boolean attempt(long deliveryId, Supplier<Boolean> work) {
        return observe(AsyncWorkTracing.Operation.WEBHOOK_DELIVERY, deliveryId, work);
    }

    @Override public boolean recovery(long deliveryId, Supplier<Boolean> work) {
        return observe(AsyncWorkTracing.Operation.WEBHOOK_RECOVERY, deliveryId, work);
    }

    private boolean observe(AsyncWorkTracing.Operation operation, long deliveryId, Supplier<Boolean> work) {
        String parent = null;
        try {
            var parents = jdbc.query(connection -> {
                var statement = connection.prepareStatement("""
                        SELECT e.traceparent FROM webhook_deliveries d
                        JOIN webhook_events e ON e.id = d.webhook_event_id WHERE d.id = ?
                        """);
                statement.setLong(1, deliveryId);
                statement.setQueryTimeout(2);
                return statement;
            }, (rs, row) -> rs.getString(1));
            parent = parents.isEmpty() ? null : parents.getFirst();
        } catch (RuntimeException ignored) {
            // Missing/malformed/unavailable metadata is diagnostic only, not a delivery failure.
        }
        return tracing.call(operation, parent, work,
                applied -> applied ? AsyncWorkTracing.Outcome.APPLIED : AsyncWorkTracing.Outcome.STALE);
    }
}
