package com.flowpay.backend.merchant.application;

import java.util.Objects;

public final class GeneratedApiKeySecret {

    private static final String REDACTED = "[REDACTED]";

    private final String rawKey;
    private final String prefix;
    private final String digest;

    public GeneratedApiKeySecret(String rawKey, String prefix, String digest) {
        this.rawKey = Objects.requireNonNull(rawKey, "rawKey must not be null");
        this.prefix = Objects.requireNonNull(prefix, "prefix must not be null");
        this.digest = Objects.requireNonNull(digest, "digest must not be null");
    }

    public String rawKey() {
        return rawKey;
    }

    public String prefix() {
        return prefix;
    }

    public String digest() {
        return digest;
    }

    @Override
    public String toString() {
        return "GeneratedApiKeySecret[rawKey=" + REDACTED
                + ", prefix=" + prefix
                + ", digest=" + REDACTED + "]";
    }
}
