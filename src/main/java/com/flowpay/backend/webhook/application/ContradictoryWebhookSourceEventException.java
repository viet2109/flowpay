package com.flowpay.backend.webhook.application;

/** Safe diagnostic: never include payloads or credentials in consumer failure logs. */
public class ContradictoryWebhookSourceEventException extends RuntimeException {
    public ContradictoryWebhookSourceEventException() {
        super("Source event conflicts with its immutable Webhook snapshot");
    }
}
