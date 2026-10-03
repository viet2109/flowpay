package com.flowpay.backend.webhook.application;

/** Encodes only the frozen public body and compares JSON values, not raw property order. */
public interface WebhookPublicPayloadCodec {
    String encode(String eventPublicId, MaterializeWebhookEventCommand command);
    boolean equivalent(String existingPayload, String eventPublicId, MaterializeWebhookEventCommand command);
}
