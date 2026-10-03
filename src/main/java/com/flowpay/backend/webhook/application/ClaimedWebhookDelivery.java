package com.flowpay.backend.webhook.application;

/** Detached immutable send snapshot; no entity or plaintext secret crosses the claim boundary. */
public record ClaimedWebhookDelivery(long deliveryId, long endpointId, int attemptNo,
                                     String eventPublicId, String url, String secretCiphertext, String payload) {
    @Override
    public String toString() {
        return "ClaimedWebhookDelivery[attemptNo=" + attemptNo + ", sendSnapshot=<redacted>]";
    }
}
