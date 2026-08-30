package com.flowpay.backend.idempotency.application;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

@Component
public class RequestFingerprintService {

    private static final String SHA_256 = "SHA-256";

    public String fingerprint(CreatePaymentFingerprint input) {
        Objects.requireNonNull(input, "input must not be null");
        StringBuilder canonical = new StringBuilder();
        appendNumber(canonical, "version", input.version());
        appendNumber(canonical, "amountMinor", input.amountMinor());
        appendText(canonical, "currency", input.currency());
        appendText(canonical, "orderId", input.orderId());
        appendText(canonical, "description", input.description());
        return sha256(canonical.toString());
    }

    public String fingerprint(ConfirmPaymentFingerprint input) {
        Objects.requireNonNull(input, "input must not be null");
        StringBuilder canonical = new StringBuilder();
        appendNumber(canonical, "version", input.version());
        appendText(canonical, "paymentPublicId", input.paymentPublicId());
        return sha256(canonical.toString());
    }

    private static void appendNumber(StringBuilder canonical, String field, long value) {
        canonical.append(field)
                .append('=')
                .append(value)
                .append('\n');
    }

    private static void appendText(StringBuilder canonical, String field, String value) {
        canonical.append(field).append('=');
        if (value == null) {
            canonical.append("-1:");
        } else {
            canonical.append(value.getBytes(StandardCharsets.UTF_8).length)
                    .append(':')
                    .append(value);
        }
        canonical.append('\n');
    }

    private static String sha256(String canonical) {
        try {
            byte[] digest = MessageDigest.getInstance(SHA_256)
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
