package com.flowpay.backend.merchant.infrastructure.security;

import com.flowpay.backend.merchant.application.ApiKeySecretCodec;
import com.flowpay.backend.merchant.application.GeneratedApiKeySecret;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;

@Component
public final class SecureApiKeySecretCodec implements ApiKeySecretCodec {

    private static final String KEY_PREFIX = "fp_test_";
    private static final int SECRET_BYTES = 32;
    private static final int LOOKUP_SECRET_LENGTH = 12;
    private static final Pattern KEY_FORMAT = Pattern.compile("fp_test_[A-Za-z0-9_-]{43}");
    private static final Pattern SHA_256_FORMAT = Pattern.compile("[0-9a-f]{64}");

    private final SecureRandom secureRandom;

    public SecureApiKeySecretCodec() {
        this(new SecureRandom());
    }

    SecureApiKeySecretCodec(SecureRandom secureRandom) {
        this.secureRandom = Objects.requireNonNull(secureRandom, "secureRandom must not be null");
    }

    @Override
    public GeneratedApiKeySecret generate() {
        byte[] randomBytes = new byte[SECRET_BYTES];
        secureRandom.nextBytes(randomBytes);
        String rawKey = KEY_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
        return new GeneratedApiKeySecret(rawKey, extractPrefix(rawKey), digest(rawKey));
    }

    @Override
    public String digest(String rawKey) {
        return HexFormat.of().formatHex(sha256(Objects.requireNonNull(rawKey, "rawKey must not be null")));
    }

    @Override
    public boolean matches(String rawKey, String expectedDigest) {
        if (rawKey == null || expectedDigest == null || !SHA_256_FORMAT.matcher(expectedDigest).matches()) {
            return false;
        }
        byte[] expected = HexFormat.of().parseHex(expectedDigest);
        return MessageDigest.isEqual(sha256(rawKey), expected);
    }

    @Override
    public boolean hasValidFormat(String rawKey) {
        return rawKey != null && KEY_FORMAT.matcher(rawKey).matches();
    }

    @Override
    public String extractPrefix(String rawKey) {
        if (!hasValidFormat(rawKey)) {
            throw new IllegalArgumentException("rawKey must use the fp_test_ format");
        }
        return rawKey.substring(0, KEY_PREFIX.length() + LOOKUP_SECRET_LENGTH);
    }

    private static byte[] sha256(String rawKey) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(rawKey.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
