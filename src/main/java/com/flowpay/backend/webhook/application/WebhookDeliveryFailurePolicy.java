package com.flowpay.backend.webhook.application;

import java.time.Instant;
import java.util.Optional;

/** Empty means DEAD; otherwise the returned instant is the next eligible attempt. */
public interface WebhookDeliveryFailurePolicy {
    Optional<Instant> nextAttemptAt(String deliveryPublicId, int failedAttemptNo, Instant now);
}
