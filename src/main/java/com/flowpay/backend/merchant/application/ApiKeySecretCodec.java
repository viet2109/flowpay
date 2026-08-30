package com.flowpay.backend.merchant.application;

public interface ApiKeySecretCodec {

    GeneratedApiKeySecret generate();

    String digest(String rawKey);

    boolean matches(String rawKey, String expectedDigest);

    boolean hasValidFormat(String rawKey);

    String extractPrefix(String rawKey);
}
