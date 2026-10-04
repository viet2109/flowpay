package com.flowpay.backend.webhook.infrastructure.security;

import com.flowpay.backend.webhook.application.WebhookSecretGenerator;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;

final class SecureWebhookSecretGenerator implements WebhookSecretGenerator {

    private static final String SECRET_PREFIX = "whsec_";
    private static final int SECRET_BYTES = 32;

    private final SecureRandom secureRandom;

    SecureWebhookSecretGenerator() {
        this(new SecureRandom());
    }

    SecureWebhookSecretGenerator(SecureRandom secureRandom) {
        this.secureRandom = Objects.requireNonNull(
                secureRandom,
                "secureRandom must not be null"
        );
    }

    @Override
    public String generate() {
        byte[] randomBytes = new byte[SECRET_BYTES];
        secureRandom.nextBytes(randomBytes);
        return SECRET_PREFIX
                + Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }
}
