package com.flowpay.backend.webhook.application;

public interface WebhookSecretCipher {

    String encrypt(String rawSecret);

    String decrypt(String ciphertext);
}
