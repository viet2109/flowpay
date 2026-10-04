package com.flowpay.backend.webhook.application;

import com.flowpay.backend.webhook.domain.WebhookDelivery;
import com.flowpay.backend.webhook.domain.WebhookDeliveryAttempt;
import com.flowpay.backend.webhook.domain.WebhookDeliveryStatus;
import com.flowpay.backend.webhook.domain.WebhookEndpointStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class WebhookDeliveryExecutionService {
    private final WebhookDeliveryRepository deliveries;
    private final WebhookEndpointRepository endpoints;
    private final WebhookEventRepository events;
    private final WebhookDeliveryAttemptRepository attempts;
    private final WebhookDeliveryFailurePolicy failurePolicy;
    private final WebhookDeliveryWorkerProperties properties;
    private final Clock clock;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<WebhookDelivery> candidates() {
        return deliveries.findClaimCandidates(clock.instant(), properties.batchSize());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<WebhookDelivery> expiredCandidates() {
        return deliveries.findRecoveryCandidates(clock.instant(), properties.batchSize());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean recoverExpired(long deliveryId, long endpointId, int expectedAttemptNo) {
        var endpoint = endpoints.findByInternalIdForShare(endpointId, true).orElse(null);
        if (endpoint == null) return false;
        var delivery = deliveries.findByInternalIdForUpdateSkipLocked(deliveryId).orElse(null);
        var now = clock.instant();
        if (delivery == null || delivery.webhookEndpointId() != endpointId
                || delivery.status() != WebhookDeliveryStatus.DELIVERING || delivery.attemptCount() != expectedAttemptNo
                || delivery.leaseExpiresAt().isAfter(now)) return false;
        var attempt = attempts.findByDeliveryIdAndAttemptNo(deliveryId, expectedAttemptNo).orElseThrow();
        if (attempt.finishedAt() != null) return false;
        if (now.isBefore(delivery.updatedAt())) now = delivery.updatedAt();
        delivery.expireLease(expectedAttemptNo, nextAttemptAt(delivery, endpoint.status(), now).orElse(null), now);
        attempt.abandon(now);
        attempts.complete(attempt);
        deliveries.save(delivery);
        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<ClaimedWebhookDelivery> claim(long deliveryId, long endpointId) {
        // Endpoint first, consistent with disable/materialization. Both claim locks skip contention.
        var endpoint = endpoints.findByInternalIdForShare(endpointId, true).orElse(null);
        if (endpoint == null || endpoint.status() != WebhookEndpointStatus.ACTIVE) return Optional.empty();
        var delivery = deliveries.findByInternalIdForUpdateSkipLocked(deliveryId).orElse(null);
        var now = clock.instant();
        if (delivery == null || delivery.webhookEndpointId() != endpointId
                || (delivery.status() != WebhookDeliveryStatus.PENDING && delivery.status() != WebhookDeliveryStatus.RETRYING)
                || delivery.nextAttemptAt().isAfter(now)) return Optional.empty();
        var event = events.findByInternalId(delivery.webhookEventId()).orElseThrow();
        int attempt = delivery.claim(now, now.plus(properties.leaseTimeout()));
        deliveries.save(delivery);
        attempts.insert(WebhookDeliveryAttempt.open(deliveryId, attempt, now));
        return Optional.of(new ClaimedWebhookDelivery(deliveryId, endpointId, attempt,
                event.publicId(), endpoint.url(), endpoint.secretCiphertext(), event.payload()));
    }

    /** False means stale/already finalized: do not touch either delivery or historical attempt. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean finalizeResult(ClaimedWebhookDelivery claim, WebhookHttpDeliveryResult result) {
        var endpoint = endpoints.findByInternalIdForShare(claim.endpointId(), false).orElseThrow();
        var delivery = deliveries.findByInternalIdForUpdate(claim.deliveryId()).orElseThrow();
        if (delivery.webhookEndpointId() != claim.endpointId() || delivery.status() != WebhookDeliveryStatus.DELIVERING
                || delivery.attemptCount() != claim.attemptNo()) return false;
        var attempt = attempts.findByDeliveryIdAndAttemptNo(claim.deliveryId(), claim.attemptNo()).orElseThrow();
        if (attempt.finishedAt() != null) return false;
        var now = clock.instant();
        if (now.isBefore(delivery.updatedAt())) now = delivery.updatedAt();
        if (result.successful()) {
            delivery.markDelivered(claim.attemptNo(), result.httpStatus(), now);
        } else {
            var next = nextAttemptAt(delivery, endpoint.status(), now);
            if (next.isPresent()) {
                delivery.scheduleRetry(claim.attemptNo(), result.httpStatus(), result.errorMessage(), next.get(), now);
            } else {
                delivery.markDead(claim.attemptNo(), result.httpStatus(), result.errorMessage(), now);
            }
        }
        attempt.complete(result.httpStatus(), (int) Math.min(Integer.MAX_VALUE, result.durationMs()), result.errorMessage(), now);
        attempts.complete(attempt);
        deliveries.save(delivery);
        return true;
    }

    private Optional<Instant> nextAttemptAt(WebhookDelivery delivery, WebhookEndpointStatus endpointStatus, Instant now) {
        return endpointStatus == WebhookEndpointStatus.ACTIVE
                ? failurePolicy.nextAttemptAt(delivery.publicId(), delivery.attemptCount(), now) : Optional.empty();
    }
}
