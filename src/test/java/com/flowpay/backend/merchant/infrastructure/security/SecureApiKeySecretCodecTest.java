package com.flowpay.backend.merchant.infrastructure.security;

import com.flowpay.backend.merchant.application.GeneratedApiKeySecret;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecureApiKeySecretCodecTest {

    private final SecureApiKeySecretCodec codec = new SecureApiKeySecretCodec();

    @Test
    void shouldGenerateHighEntropyApiKeysWithUniqueLookupPrefixes() {
        Set<String> rawKeys = new HashSet<>();
        Set<String> prefixes = new HashSet<>();
        Set<String> digests = new HashSet<>();

        for (int index = 0; index < 256; index++) {
            GeneratedApiKeySecret generated = codec.generate();

            assertThat(generated.rawKey()).matches("fp_test_[A-Za-z0-9_-]{43}");
            assertThat(generated.prefix()).matches("fp_test_[A-Za-z0-9_-]{12}");
            assertThat(generated.rawKey()).startsWith(generated.prefix());
            assertThat(generated.digest()).matches("[0-9a-f]{64}");
            assertThat(codec.matches(generated.rawKey(), generated.digest())).isTrue();

            rawKeys.add(generated.rawKey());
            prefixes.add(generated.prefix());
            digests.add(generated.digest());
        }

        assertThat(rawKeys).hasSize(256);
        assertThat(prefixes).hasSize(256);
        assertThat(digests).hasSize(256);
    }

    @Test
    void shouldVerifyCompleteDigestAndRejectModifiedSecret() {
        GeneratedApiKeySecret generated = codec.generate();
        String modified = generated.rawKey().substring(0, generated.rawKey().length() - 1)
                + differentCharacter(generated.rawKey().charAt(generated.rawKey().length() - 1));

        assertThat(codec.digest(generated.rawKey())).isEqualTo(generated.digest());
        assertThat(codec.matches(generated.rawKey(), generated.digest())).isTrue();
        assertThat(codec.matches(modified, generated.digest())).isFalse();
        assertThat(codec.matches(generated.rawKey(), "not-a-digest")).isFalse();
        assertThat(codec.matches(null, generated.digest())).isFalse();
    }

    @Test
    void shouldRejectMalformedKeyAndRedactGeneratedSecretText() {
        GeneratedApiKeySecret generated = codec.generate();

        assertThat(codec.hasValidFormat("fp_live_secret")).isFalse();
        assertThatThrownBy(() -> codec.extractPrefix("fp_test_short"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(generated.toString())
                .contains(generated.prefix())
                .doesNotContain(generated.rawKey())
                .doesNotContain(generated.digest());
    }

    private static char differentCharacter(char current) {
        return current == 'A' ? 'B' : 'A';
    }
}
