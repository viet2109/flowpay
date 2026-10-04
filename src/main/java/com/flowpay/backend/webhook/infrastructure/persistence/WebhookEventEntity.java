package com.flowpay.backend.webhook.infrastructure.persistence;

import com.flowpay.backend.webhook.domain.WebhookResourceType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;

@Entity
@Table(name = "webhook_events")
@Immutable
@Getter(AccessLevel.PACKAGE)
@Accessors(fluent = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class WebhookEventEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "public_id", nullable = false, updatable = false)
    private String publicId;
    @Column(name = "source_event_id", nullable = false, updatable = false)
    private String sourceEventId;
    @Column(name = "merchant_id", nullable = false, updatable = false)
    private long merchantId;
    @Column(name = "event_type", nullable = false, updatable = false)
    private String eventType;
    @Enumerated(EnumType.STRING)
    @Column(name = "resource_type", nullable = false, updatable = false)
    private WebhookResourceType resourceType;
    @Column(name = "resource_id", nullable = false, updatable = false)
    private String resourceId;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false, columnDefinition = "jsonb")
    private String payload;
    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
