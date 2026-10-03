package com.flowpay.backend.webhook.application;

/** One bounded attempt, outside a database transaction; scheduling/retry belongs to the worker. */
@FunctionalInterface
public interface WebhookHttpClientPort {
    WebhookHttpDeliveryResult send(WebhookHttpDeliveryRequest request);
}
