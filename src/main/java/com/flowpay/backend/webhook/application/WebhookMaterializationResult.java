package com.flowpay.backend.webhook.application;

public record WebhookMaterializationResult(Outcome outcome, String eventPublicId, int newDeliveryCount) {
    public enum Outcome { MATERIALIZED, ALREADY_MATERIALIZED }
}
