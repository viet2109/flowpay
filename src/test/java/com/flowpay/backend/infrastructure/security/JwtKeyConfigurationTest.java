package com.flowpay.backend.infrastructure.security;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class JwtKeyConfigurationTest {

    private final JwtKeyConfiguration configuration = new JwtKeyConfiguration();

    @Test
    void shouldGenerateEphemeralRsaKeyPairForDevelopmentAndTests() {
        JwtRsaKeyPair keyPair = configuration.jwtRsaKeyPair(properties(true));

        assertThat(keyPair.publicKey().getAlgorithm()).isEqualTo("RSA");
        assertThat(keyPair.publicKey().getModulus().bitLength()).isGreaterThanOrEqualTo(2048);
        assertThat(keyPair.privateKey().getModulus()).isEqualTo(keyPair.publicKey().getModulus());
    }

    @Test
    void shouldRequireExternalKeyLocationsWhenEphemeralKeysAreDisabled() {
        assertThatIllegalStateException()
                .isThrownBy(() -> configuration.jwtRsaKeyPair(properties(false)))
                .withMessage("JWT public and private key locations must be configured");
    }

    private static JwtSecurityProperties properties(boolean ephemeral) {
        return new JwtSecurityProperties("https://flowpay.dev", Duration.ofMinutes(15), ephemeral, null, null);
    }
}
