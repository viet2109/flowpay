package com.flowpay.backend.webhook.infrastructure.persistence;

import com.flowpay.backend.webhook.application.WebhookDeliveryAttemptView;
import com.flowpay.backend.webhook.application.WebhookDeliveryQueryRepository;
import com.flowpay.backend.webhook.application.WebhookDeliveryView;
import com.flowpay.backend.webhook.application.WebhookDeliveryViewPage;
import com.flowpay.backend.webhook.domain.WebhookDeliveryStatus;
import com.flowpay.backend.webhook.domain.WebhookEventType;
import com.flowpay.backend.webhook.domain.WebhookResourceType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class WebhookDeliveryQueryRepositoryAdapter implements WebhookDeliveryQueryRepository {
    private static final String OWNED_JOIN = """
            FROM webhook_deliveries d
            JOIN webhook_events e ON e.id = d.webhook_event_id
            JOIN webhook_endpoints p ON p.id = d.webhook_endpoint_id
            WHERE e.merchant_id = ? AND p.merchant_id = ?
            """;
    private static final String PROJECTION = """
            SELECT d.id, d.webhook_endpoint_id, d.public_id, p.public_id AS endpoint_public_id,
                e.public_id AS event_public_id, e.event_type, e.resource_type, e.resource_id,
                d.status, d.attempt_count, d.next_attempt_at, d.delivered_at,
                d.last_http_status, d.last_error, d.created_at, d.updated_at
            """;

    private final JdbcTemplate jdbc;

    @Override
    public WebhookDeliveryViewPage findByMerchantId(long merchantId, WebhookDeliveryStatus status,
            String endpointPublicId, WebhookEventType eventType, int page, int size) {
        var parameters = new ArrayList<Object>(List.of(merchantId, merchantId));
        var predicate = new StringBuilder(OWNED_JOIN);
        if (status != null) {
            predicate.append(" AND d.status = ?");
            parameters.add(status.name());
        }
        if (endpointPublicId != null) {
            predicate.append(" AND p.public_id = ?");
            parameters.add(endpointPublicId);
        }
        if (eventType != null) {
            predicate.append(" AND e.event_type = ?");
            parameters.add(eventType.value());
        }
        long total = jdbc.queryForObject("SELECT COUNT(*) " + predicate, Long.class, parameters.toArray());
        parameters.add(size);
        parameters.add((long) page * size);
        List<WebhookDeliveryView> content = jdbc.query(PROJECTION + predicate
                + " ORDER BY d.created_at DESC, d.id DESC LIMIT ? OFFSET ?", this::toView, parameters.toArray());
        var result = new PageImpl<>(content, PageRequest.of(page, size), total);
        return new WebhookDeliveryViewPage(result.getContent(), page, size, result.getTotalElements(),
                result.getTotalPages(), (long) page + 1 < result.getTotalPages(), result.hasPrevious());
    }

    @Override
    public Optional<WebhookDeliveryView> findByPublicIdAndMerchantId(String publicId, long merchantId) {
        return jdbc.query(PROJECTION + OWNED_JOIN + " AND d.public_id = ?", this::toView,
                merchantId, merchantId, publicId).stream().findFirst();
    }

    @Override
    public List<WebhookDeliveryAttemptView> findAttemptsByDeliveryId(long deliveryId) {
        return List.copyOf(jdbc.query("""
                SELECT attempt_no, started_at, finished_at, http_status, duration_ms, error_message
                FROM webhook_delivery_attempts WHERE delivery_id = ? ORDER BY attempt_no ASC
                """, (row, rowNumber) -> new WebhookDeliveryAttemptView(row.getInt("attempt_no"),
                instant(row, "started_at"), instant(row, "finished_at"), row.getObject("http_status", Integer.class),
                row.getObject("duration_ms", Integer.class), row.getString("error_message")), deliveryId));
    }

    private WebhookDeliveryView toView(ResultSet row, int rowNumber) throws SQLException {
        return new WebhookDeliveryView(row.getLong("id"), row.getLong("webhook_endpoint_id"),
                row.getString("public_id"), row.getString("endpoint_public_id"), row.getString("event_public_id"),
                row.getString("event_type"), WebhookResourceType.valueOf(row.getString("resource_type")),
                row.getString("resource_id"), WebhookDeliveryStatus.valueOf(row.getString("status")),
                row.getInt("attempt_count"), instant(row, "next_attempt_at"), instant(row, "delivered_at"),
                row.getObject("last_http_status", Integer.class), row.getString("last_error"),
                instant(row, "created_at"), instant(row, "updated_at"));
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
