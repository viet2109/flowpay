package com.flowpay.backend.webhook.infrastructure.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.Arrays;
import java.util.Base64;

@ConfigurationProperties(prefix = "flowpay.webhook.secret")
public final class WebhookSecretProperties {

    private static final int AES_256_KEY_BYTES = 32;
    private static final String REDACTED = "[REDACTED]";

    private final SecretKey encryptionKey;

    public WebhookSecretProperties(String encryptionKey) {
        if (encryptionKey == null || encryptionKey.isBlank()) {
            throw new IllegalArgumentException(
                    "webhook secret encryption key must be configured"
            );
        }

        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(encryptionKey.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "webhook secret encryption key must be valid Base64",
                    exception
            );
        }
        if (decoded.length != AES_256_KEY_BYTES) {
            Arrays.fill(decoded, (byte) 0);
            throw new IllegalArgumentException(
                    "webhook secret encryption key must decode to exactly 32 bytes"
            );
        }
        this.encryptionKey = new SecretKeySpec(decoded, "AES");
        Arrays.fill(decoded, (byte) 0);
    }

    SecretKey encryptionKey() {
        return encryptionKey;
    }

    @Override
    public String toString() {
        return "WebhookSecretProperties[encryptionKey=" + REDACTED + "]";
    }
}
