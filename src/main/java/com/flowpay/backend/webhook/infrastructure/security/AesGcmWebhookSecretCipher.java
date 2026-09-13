package com.flowpay.backend.webhook.infrastructure.security;

import com.flowpay.backend.webhook.application.WebhookSecretCipher;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;

final class AesGcmWebhookSecretCipher implements WebhookSecretCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String FORMAT_VERSION = "v1";
    private static final int AES_256_KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKey encryptionKey;
    private final SecureRandom secureRandom;

    AesGcmWebhookSecretCipher(SecretKey encryptionKey) {
        this(encryptionKey, new SecureRandom());
    }

    AesGcmWebhookSecretCipher(SecretKey encryptionKey, SecureRandom secureRandom) {
        this.encryptionKey = copyAndValidateKey(encryptionKey);
        this.secureRandom = Objects.requireNonNull(
                secureRandom,
                "secureRandom must not be null"
        );
    }

    @Override
    public String encrypt(String rawSecret) {
        String secret = requireText(rawSecret, "rawSecret");
        byte[] iv = new byte[IV_BYTES];
        secureRandom.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(secret.getBytes(StandardCharsets.UTF_8));
            return FORMAT_VERSION
                    + ":" + encode(iv)
                    + ":" + encode(encrypted);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Could not encrypt webhook secret", exception);
        }
    }

    @Override
    public String decrypt(String ciphertext) {
        String encryptedSecret = requireText(ciphertext, "ciphertext");
        String[] parts = encryptedSecret.split(":", -1);
        if (parts.length != 3 || !FORMAT_VERSION.equals(parts[0])) {
            throw malformedCiphertext(null);
        }

        byte[] iv;
        byte[] encrypted;
        try {
            iv = Base64.getUrlDecoder().decode(parts[1]);
            encrypted = Base64.getUrlDecoder().decode(parts[2]);
        } catch (IllegalArgumentException exception) {
            throw malformedCiphertext(exception);
        }
        if (iv.length != IV_BYTES || encrypted.length <= TAG_BITS / Byte.SIZE) {
            throw malformedCiphertext(null);
        }

        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey, new GCMParameterSpec(TAG_BITS, iv));
            byte[] decrypted = cipher.doFinal(encrypted);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (AEADBadTagException exception) {
            throw new IllegalArgumentException(
                    "Webhook secret ciphertext authentication failed",
                    exception
            );
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Could not decrypt webhook secret", exception);
        }
    }

    private static SecretKey copyAndValidateKey(SecretKey encryptionKey) {
        Objects.requireNonNull(encryptionKey, "encryptionKey must not be null");
        byte[] encoded = encryptionKey.getEncoded();
        if (encoded == null || encoded.length != AES_256_KEY_BYTES) {
            throw new IllegalArgumentException("encryptionKey must contain exactly 32 bytes");
        }
        SecretKey copied = new SecretKeySpec(encoded, "AES");
        Arrays.fill(encoded, (byte) 0);
        return copied;
    }

    private static String encode(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static IllegalArgumentException malformedCiphertext(Throwable cause) {
        return new IllegalArgumentException("Malformed webhook secret ciphertext", cause);
    }
}
