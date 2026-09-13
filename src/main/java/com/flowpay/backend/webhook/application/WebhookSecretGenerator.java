package com.flowpay.backend.webhook.application;

@FunctionalInterface
public interface WebhookSecretGenerator {

    String generate();
}
