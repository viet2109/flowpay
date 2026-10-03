package com.flowpay.backend.webhook.infrastructure.security;

import com.flowpay.backend.webhook.application.WebhookSigner;
import org.springframework.stereotype.Component;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

@Component
public final class HmacSha256WebhookSigner implements WebhookSigner {
    @Override
    public String signatureHeader(long unixTimestamp, String secret, byte[] body) {
        Objects.requireNonNull(secret, "Webhook secret must not be null");
        Objects.requireNonNull(body, "Webhook body must not be null");
        if (unixTimestamp < 0 || secret.isBlank()) {
            throw new IllegalArgumentException("Invalid Webhook signing input");
        }
        byte[] key = secret.getBytes(StandardCharsets.UTF_8);
        try {
            // Mac is attempt-local: the singleton signer is safe for concurrent workers.
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            mac.update((unixTimestamp + ".").getBytes(StandardCharsets.US_ASCII));
            return "t=" + unixTimestamp + ",v1=" + HexFormat.of().formatHex(mac.doFinal(body));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Could not sign Webhook body");
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }
}
