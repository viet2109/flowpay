package com.flowpay.backend.webhook.application;

import com.flowpay.backend.webhook.domain.WebhookDeliveryAttempt;
import java.util.List;
import java.util.Optional;

/** Claim/finalization use cases own transactions spanning delivery and attempt writes. */
public interface WebhookDeliveryAttemptRepository {
    WebhookDeliveryAttempt insert(WebhookDeliveryAttempt openAttempt);
    /** Conditional completion; a completed or stale attempt can never be overwritten. */
    WebhookDeliveryAttempt complete(WebhookDeliveryAttempt completedAttempt);
    Optional<WebhookDeliveryAttempt> findByDeliveryIdAndAttemptNo(long deliveryId, int attemptNo);
    List<WebhookDeliveryAttempt> findAllByDeliveryId(long deliveryId);
}
