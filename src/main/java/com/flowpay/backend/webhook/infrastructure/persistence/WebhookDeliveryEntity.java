package com.flowpay.backend.webhook.infrastructure.persistence;

import com.flowpay.backend.webhook.domain.WebhookDeliveryStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import java.time.Instant;

@Entity
@Table(name = "webhook_deliveries")
@Getter(AccessLevel.PACKAGE)
@Accessors(fluent = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PACKAGE)
class WebhookDeliveryEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "public_id", nullable = false, updatable = false)
    private String publicId;
    @Column(name = "webhook_event_id", nullable = false, updatable = false)
    private long webhookEventId;
    @Column(name = "webhook_endpoint_id", nullable = false, updatable = false)
    private long webhookEndpointId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WebhookDeliveryStatus status;
    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;
    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;
    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;
    @Column(name = "delivered_at")
    private Instant deliveredAt;
    @Column(name = "last_http_status")
    private Integer lastHttpStatus;
    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Version
    @Column(nullable = false)
    private long version;
}
