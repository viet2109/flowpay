package com.flowpay.backend.webhook.infrastructure.security;

import org.junit.jupiter.api.Test;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AesGcmWebhookSecretCipherTest {

    private static final byte[] KEY = "0123456789abcdef0123456789abcdef"
            .getBytes(StandardCharsets.US_ASCII);
    private static final String RAW_SECRET =
            "whsec_9gY9A-Very-Secret-Value-With-Enough-Entropy";

    @Test
    void shouldEncryptAndDecryptUsingTheVersionedCiphertextFormat() {
        AesGcmWebhookSecretCipher cipher = cipher(KEY);

        String encrypted = cipher.encrypt(RAW_SECRET);

        assertThat(encrypted).startsWith("v1:").doesNotContain(RAW_SECRET);
        assertThat(encrypted.split(":", -1)).hasSize(3);
        assertThat(cipher.decrypt(encrypted)).isEqualTo(RAW_SECRET);
    }

    @Test
    void shouldUseARandom96BitIvForEveryEncryption() {
        AesGcmWebhookSecretCipher cipher = cipher(KEY);

        String first = cipher.encrypt(RAW_SECRET);
        String second = cipher.encrypt(RAW_SECRET);

        assertThat(first).isNotEqualTo(second);
        assertThat(Base64.getUrlDecoder().decode(first.split(":", -1)[1])).hasSize(12);
        assertThat(Base64.getUrlDecoder().decode(second.split(":", -1)[1])).hasSize(12);
        assertThat(cipher.decrypt(first)).isEqualTo(RAW_SECRET);
        assertThat(cipher.decrypt(second)).isEqualTo(RAW_SECRET);
    }

    @Test
    void shouldDetectCiphertextTampering() {
        AesGcmWebhookSecretCipher cipher = cipher(KEY);
        String[] parts = cipher.encrypt(RAW_SECRET).split(":", -1);
        byte[] encryptedBytes = Base64.getUrlDecoder().decode(parts[2]);
        encryptedBytes[0] ^= 1;
        String tampered = parts[0] + ":" + parts[1] + ":"
                + Base64.getUrlEncoder().withoutPadding().encodeToString(encryptedBytes);

        assertThatThrownBy(() -> cipher.decrypt(tampered))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Webhook secret ciphertext authentication failed");
    }

    @Test
    void shouldFailClosedWhenDecryptingWithTheWrongKey() {
        AesGcmWebhookSecretCipher cipher = cipher(KEY);
        byte[] wrongKey = "abcdef0123456789abcdef0123456789"
                .getBytes(StandardCharsets.US_ASCII);
        AesGcmWebhookSecretCipher wrongCipher = cipher(wrongKey);

        assertThatThrownBy(() -> wrongCipher.decrypt(cipher.encrypt(RAW_SECRET)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Webhook secret ciphertext authentication failed");
    }

    @Test
    void shouldRejectMalformedCiphertextAndInvalidInputs() {
        AesGcmWebhookSecretCipher cipher = cipher(KEY);

        assertThatThrownBy(() -> cipher.decrypt("v2:invalid:invalid"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Malformed webhook secret ciphertext");
        assertThatThrownBy(() -> cipher.decrypt("v1:too:few:parts"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Malformed webhook secret ciphertext");
        assertThatThrownBy(() -> cipher.encrypt(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("rawSecret must not be blank");
        assertThatThrownBy(() -> new AesGcmWebhookSecretCipher(
                new SecretKeySpec(new byte[16], "AES")
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("encryptionKey must contain exactly 32 bytes");
    }

    private static AesGcmWebhookSecretCipher cipher(byte[] key) {
        return new AesGcmWebhookSecretCipher(
                new SecretKeySpec(key, "AES"),
                new SecureRandom()
        );
    }
}
