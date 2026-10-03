package com.flowpay.backend.webhook.application;

import com.flowpay.backend.webhook.domain.WebhookDelivery;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface WebhookDeliveryRepository {
    WebhookDelivery save(WebhookDelivery delivery);
    Optional<WebhookDelivery> findByInternalId(long internalId);
    /** Requires a caller-owned transaction; locks only this module's delivery row. */
    Optional<WebhookDelivery> findByInternalIdForUpdate(long internalId);
    Optional<WebhookDelivery> findByInternalIdForUpdateSkipLocked(long internalId);
    Optional<WebhookDelivery> findByPublicIdAndMerchantId(String publicId, long merchantId);
    /** Bounded candidates, not claims. A worker must lock and recheck before sending. */
    List<WebhookDelivery> findDue(Instant now, int limit);
    /** Short caller-owned transaction; ACTIVE endpoints only, skip contended rows. Still not claims. */
    List<WebhookDelivery> findClaimCandidates(Instant now, int limit);
    List<WebhookDelivery> findExpiredLeases(Instant now, int limit);
}
