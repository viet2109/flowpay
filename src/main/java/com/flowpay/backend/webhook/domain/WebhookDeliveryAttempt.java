package com.flowpay.backend.webhook.domain;

import lombok.Getter;
import lombok.experimental.Accessors;

import java.time.Instant;
import java.util.Objects;

/** Open diagnostic history that can be completed once, never reset or deleted. */
@Getter
@Accessors(fluent = true)
public final class WebhookDeliveryAttempt {
    private final Long internalId;
    private final long deliveryId;
    private final int attemptNo;
    private final Instant startedAt;
    private Instant finishedAt;
    private Integer httpStatus;
    private Integer durationMs;
    private String errorMessage;
    private final Instant createdAt;

    private WebhookDeliveryAttempt(Long internalId, long deliveryId, int attemptNo, Instant startedAt,
                                   Instant finishedAt, Integer httpStatus, Integer durationMs,
                                   String errorMessage, Instant createdAt) {
        if ((internalId != null && internalId <= 0) || deliveryId <= 0 || attemptNo <= 0) {
            throw new IllegalArgumentException("attempt identity and attemptNo must be positive");
        }
        this.internalId = internalId;
        this.deliveryId = deliveryId;
        this.attemptNo = attemptNo;
        this.startedAt = Objects.requireNonNull(startedAt, "startedAt must not be null");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        if (finishedAt == null) {
            if (httpStatus != null || durationMs != null || errorMessage != null) {
                throw new IllegalArgumentException("open attempts cannot contain a result");
            }
        } else {
            validateResult(finishedAt, httpStatus, durationMs, errorMessage);
        }
        this.finishedAt = finishedAt;
        this.httpStatus = httpStatus;
        this.durationMs = durationMs;
        this.errorMessage = optionalError(errorMessage);
    }

    public static WebhookDeliveryAttempt open(long deliveryId, int attemptNo, Instant now) {
        return new WebhookDeliveryAttempt(null, deliveryId, attemptNo, now, null, null, null, null, now);
    }

    public static WebhookDeliveryAttempt rehydrate(long internalId, long deliveryId, int attemptNo,
                                                   Instant startedAt, Instant finishedAt, Integer httpStatus,
                                                   Integer durationMs, String errorMessage, Instant createdAt) {
        return new WebhookDeliveryAttempt(internalId, deliveryId, attemptNo, startedAt, finishedAt,
                httpStatus, durationMs, errorMessage, createdAt);
    }

    public void complete(Integer httpStatus, Integer durationMs, String errorMessage, Instant finishedAt) {
        if (this.finishedAt != null) {
            throw new IllegalStateException("completed attempt is immutable");
        }
        validateResult(finishedAt, httpStatus, durationMs, errorMessage);
        this.finishedAt = finishedAt;
        this.httpStatus = httpStatus;
        this.durationMs = durationMs;
        this.errorMessage = optionalError(errorMessage);
    }

    public void abandon(Instant finishedAt) {
        // Actual HTTP status/duration are unknown after a worker crash; do not fabricate them.
        complete(null, null, "DELIVERY_LEASE_EXPIRED", finishedAt);
    }

    private void validateResult(Instant finishedAt, Integer httpStatus, Integer durationMs, String error) {
        Instant finish = Objects.requireNonNull(finishedAt, "finishedAt must not be null");
        if (finish.isBefore(startedAt) || finish.isBefore(createdAt)) {
            throw new IllegalArgumentException("finishedAt must not precede attempt start/creation");
        }
        if (httpStatus != null && (httpStatus < 100 || httpStatus > 599)) {
            throw new IllegalArgumentException("httpStatus must be between 100 and 599");
        }
        if (durationMs != null && durationMs < 0) {
            throw new IllegalArgumentException("durationMs must not be negative");
        }
        if (httpStatus == null && optionalError(error) == null) {
            throw new IllegalArgumentException("completed attempt must have a status or normalized error");
        }
    }

    private static String optionalError(String error) {
        return WebhookDiagnostic.bounded(error);
    }
}
