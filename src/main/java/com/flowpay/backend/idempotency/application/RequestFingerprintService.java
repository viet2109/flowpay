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

    public String fingerprint(RequestFingerprintInput input) {
        Objects.requireNonNull(input, "input must not be null");
        RequestFingerprintCanonicalizer canonicalizer =
                new RequestFingerprintCanonicalizer();
        input.appendTo(canonicalizer);
        return sha256(canonicalizer.value());
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
