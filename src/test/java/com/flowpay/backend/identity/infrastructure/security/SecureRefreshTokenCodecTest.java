package com.flowpay.backend.identity.infrastructure.security;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SecureRefreshTokenCodecTest {

    private final SecureRefreshTokenCodec codec = new SecureRefreshTokenCodec();

    @Test
    void shouldGenerateHighEntropyUrlSafeTokens() {
        Set<String> generated = new HashSet<>();
        for (int index = 0; index < 100; index++) {
            generated.add(codec.generate());
        }

        assertThat(generated).hasSize(100).allMatch(codec::hasValidFormat);
    }

    @Test
    void shouldProduceDeterministicSha256DigestWithoutRawToken() {
        String rawToken = codec.generate();

        String digest = codec.digest(rawToken);

        assertThat(digest).hasSize(64).matches("[0-9a-f]{64}").isEqualTo(codec.digest(rawToken));
        assertThat(digest).doesNotContain(rawToken);
    }
}
