package com.flowpay.backend.webhook.domain;

import lombok.Getter;
import lombok.experimental.Accessors;

import java.time.Instant;
import java.util.Objects;

@Getter
@Accessors(fluent = true)
public final class WebhookDelivery {
    private final Long internalId;
    private final String publicId;
    private final long webhookEventId;
    private final long webhookEndpointId;
    private WebhookDeliveryStatus status;
    private int attemptCount;
    private Instant nextAttemptAt;
    private Instant leaseExpiresAt;
    private Instant deliveredAt;
    private Integer lastHttpStatus;
    private String lastError;
    private final Instant createdAt;
    private Instant updatedAt;
    private final long version;

    private WebhookDelivery(Long internalId, String publicId, long webhookEventId, long webhookEndpointId,
                            WebhookDeliveryStatus status, int attemptCount, Instant nextAttemptAt,
                            Instant leaseExpiresAt, Instant deliveredAt, Integer lastHttpStatus, String lastError,
                            Instant createdAt, Instant updatedAt, long version) {
        if ((internalId != null && internalId <= 0) || webhookEventId <= 0 || webhookEndpointId <= 0) {
            throw new IllegalArgumentException("delivery references must be positive");
        }
        String id = Objects.requireNonNull(publicId, "publicId must not be null").trim();
        if (!id.startsWith("wdl_") || id.length() <= 4 || id.length() > 64) {
            throw new IllegalArgumentException("publicId must use wdl_ and fit 64 characters");
        }
        if (attemptCount < 0 || version < 0) {
            throw new IllegalArgumentException("attemptCount and version must not be negative");
        }
        this.internalId = internalId;
        this.publicId = id;
        this.webhookEventId = webhookEventId;
        this.webhookEndpointId = webhookEndpointId;
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.attemptCount = attemptCount;
        this.nextAttemptAt = nextAttemptAt;
        this.leaseExpiresAt = leaseExpiresAt;
        this.deliveredAt = deliveredAt;
        this.lastHttpStatus = httpStatus(lastHttpStatus);
        this.lastError = optionalError(lastError);
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        this.version = version;
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not be before createdAt");
        }
        validateState();
    }

    public static WebhookDelivery create(String publicId, long webhookEventId, long webhookEndpointId, Instant now) {
        return new WebhookDelivery(null, publicId, webhookEventId, webhookEndpointId,
                WebhookDeliveryStatus.PENDING, 0, now, null, null, null, null, now, now, 0);
    }

    public static WebhookDelivery rehydrate(long internalId, String publicId, long webhookEventId,
                                            long webhookEndpointId, WebhookDeliveryStatus status, int attemptCount,
                                            Instant nextAttemptAt, Instant leaseExpiresAt, Instant deliveredAt,
                                            Integer lastHttpStatus, String lastError, Instant createdAt,
                                            Instant updatedAt, long version) {
        return new WebhookDelivery(internalId, publicId, webhookEventId, webhookEndpointId, status,
                attemptCount, nextAttemptAt, leaseExpiresAt, deliveredAt, lastHttpStatus, lastError,
                createdAt, updatedAt, version);
    }

    /** Returns the new attempt number, which fences all later finalization. */
    public int claim(Instant now, Instant leaseExpiresAt) {
        if (status != WebhookDeliveryStatus.PENDING && status != WebhookDeliveryStatus.RETRYING) {
            throw new IllegalStateException("only scheduled deliveries can be claimed");
        }
        Instant changedAt = changeTime(now);
        if (nextAttemptAt.isAfter(changedAt)) {
            throw new IllegalStateException("delivery is not due");
        }
        Instant expiry = Objects.requireNonNull(leaseExpiresAt, "leaseExpiresAt must not be null");
        if (!expiry.isAfter(changedAt)) {
            throw new IllegalArgumentException("lease must expire after claim time");
        }
        int nextAttempt = Math.addExact(attemptCount, 1);
        status = WebhookDeliveryStatus.DELIVERING;
        attemptCount = nextAttempt;
        nextAttemptAt = null;
        this.leaseExpiresAt = expiry;
        updatedAt = changedAt;
        return nextAttempt;
    }

    public void markDelivered(int expectedAttemptNo, int httpStatus, Instant now) {
        requireCurrentAttempt(expectedAttemptNo);
        Integer code = httpStatus(httpStatus);
        if (code < 200 || code > 299) {
            throw new IllegalArgumentException("delivered result must be HTTP 2xx");
        }
        Instant changedAt = changeTime(now);
        status = WebhookDeliveryStatus.DELIVERED;
        leaseExpiresAt = null;
        deliveredAt = changedAt;
        lastHttpStatus = code;
        lastError = null;
        updatedAt = changedAt;
    }

    public void scheduleRetry(int expectedAttemptNo, Integer httpStatus, String error,
                              Instant nextAttemptAt, Instant now) {
        requireCurrentAttempt(expectedAttemptNo);
        Instant changedAt = changeTime(now);
        Instant next = Objects.requireNonNull(nextAttemptAt, "nextAttemptAt must not be null");
        if (next.isBefore(changedAt)) {
            throw new IllegalArgumentException("retry must not be scheduled in the past");
        }
        String safeError = failure(httpStatus, error);
        status = WebhookDeliveryStatus.RETRYING;
        leaseExpiresAt = null;
        this.nextAttemptAt = next;
        lastHttpStatus = httpStatus;
        lastError = safeError;
        updatedAt = changedAt;
    }

    public void markDead(int expectedAttemptNo, Integer httpStatus, String error, Instant now) {
        requireCurrentAttempt(expectedAttemptNo);
        Instant changedAt = changeTime(now);
        String safeError = failure(httpStatus, error);
        status = WebhookDeliveryStatus.DEAD;
        leaseExpiresAt = null;
        lastHttpStatus = httpStatus;
        lastError = safeError;
        updatedAt = changedAt;
    }

    public void retryManually(Instant now) {
        if (status != WebhookDeliveryStatus.DEAD) {
            throw new IllegalStateException("manual retry requires DEAD delivery");
        }
        Instant changedAt = changeTime(now);
        status = WebhookDeliveryStatus.RETRYING;
        nextAttemptAt = changedAt;
        updatedAt = changedAt;
        // Attempt count and diagnostic history intentionally survive manual retry.
    }

    public void stopForDisabledEndpoint(Instant now) {
        if (status != WebhookDeliveryStatus.PENDING && status != WebhookDeliveryStatus.RETRYING) {
            throw new IllegalStateException("only scheduled deliveries can be stopped before claim");
        }
        Instant changedAt = changeTime(now);
        status = WebhookDeliveryStatus.DEAD;
        nextAttemptAt = null;
        lastError = "ENDPOINT_DISABLED";
        updatedAt = changedAt;
    }

    private void requireCurrentAttempt(int expectedAttemptNo) {
        if (status != WebhookDeliveryStatus.DELIVERING || expectedAttemptNo <= 0 || expectedAttemptNo != attemptCount) {
            throw new IllegalStateException("result does not belong to the current delivery attempt");
        }
    }

    private Instant changeTime(Instant now) {
        Instant value = Objects.requireNonNull(now, "now must not be null");
        if (value.isBefore(updatedAt)) {
            throw new IllegalArgumentException("change time must not be before updatedAt");
        }
        return value;
    }

    private void validateState() {
        boolean valid = switch (status) {
            case PENDING, RETRYING -> nextAttemptAt != null && leaseExpiresAt == null && deliveredAt == null;
            case DELIVERING -> nextAttemptAt == null && leaseExpiresAt != null && deliveredAt == null && attemptCount > 0;
            case DELIVERED -> nextAttemptAt == null && leaseExpiresAt == null && deliveredAt != null && attemptCount > 0;
            case DEAD -> nextAttemptAt == null && leaseExpiresAt == null && deliveredAt == null;
        };
        if (!valid) {
            throw new IllegalArgumentException("status and delivery timestamps are inconsistent");
        }
        if (deliveredAt != null && (deliveredAt.isBefore(createdAt) || deliveredAt.isAfter(updatedAt))) {
            throw new IllegalArgumentException("deliveredAt must be within the delivery lifetime");
        }
    }

    private static Integer httpStatus(Integer status) {
        if (status != null && (status < 100 || status > 599)) {
            throw new IllegalArgumentException("httpStatus must be between 100 and 599");
        }
        return status;
    }

    private static String optionalError(String error) {
        return error == null || error.isBlank() ? null : error.trim();
    }

    private static String failure(Integer status, String error) {
        httpStatus(status);
        String value = optionalError(error);
        if ((status == null && value == null) || (status != null && status >= 200 && status <= 299)) {
            throw new IllegalArgumentException("failure must carry a non-2xx status or a normalized error");
        }
        return value;
    }
}
