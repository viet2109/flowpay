package com.flowpay.backend.webhook.application;

import com.flowpay.backend.webhook.domain.WebhookEvent;
import java.util.Optional;

/** Insert/read only. The caller owns the materialization transaction. */
public interface WebhookEventRepository {
    /** Empty on source-event dedupe conflict; compare existing facts in the use case. */
    Optional<WebhookEvent> tryInsert(WebhookEvent event);
    Optional<WebhookEvent> findBySourceEventId(String sourceEventId);
    Optional<WebhookEvent> findByInternalId(long internalId);
}
