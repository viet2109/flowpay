package com.flowpay.backend.webhook.application;

/** Signs unix seconds + '.' + the exact outbound bytes; never serializes or normalizes the body/key. */
@FunctionalInterface
public interface WebhookSigner {
    String signatureHeader(long unixTimestamp, String secret, byte[] body);
}
