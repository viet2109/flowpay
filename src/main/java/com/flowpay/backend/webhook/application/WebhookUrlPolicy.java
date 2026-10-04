package com.flowpay.backend.webhook.application;

@FunctionalInterface
public interface WebhookUrlPolicy {

    String validate(String url);
}
