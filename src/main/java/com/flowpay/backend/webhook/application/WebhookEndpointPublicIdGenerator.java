package com.flowpay.backend.webhook.application;

@FunctionalInterface
public interface WebhookEndpointPublicIdGenerator {

    String nextId();
}
