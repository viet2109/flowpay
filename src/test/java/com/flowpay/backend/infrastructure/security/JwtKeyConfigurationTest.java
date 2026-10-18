package com.flowpay.backend.infrastructure.security;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;

import java.time.Duration;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

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

    @Test
    void shouldLoadAndReusePersistentMatchingRsaKeys() throws Exception {
        KeyPair pair = rsa(2048);
        JwtSecurityProperties properties = persistent(pair, pair);
        JwtRsaKeyPair first = configuration.jwtRsaKeyPair(properties);
        JwtRsaKeyPair second = configuration.jwtRsaKeyPair(properties);
        assertThat(first.publicKey()).isEqualTo(second.publicKey());
        assertThat(first.privateKey()).isEqualTo(second.privateKey());
    }

    @Test
    void shouldRejectMismatchedKeyPairs() throws Exception {
        assertInvalid(persistent(rsa(2048), rsa(2048)));
    }

    @Test
    void shouldRejectWeakRsaKeys() throws Exception {
        KeyPair weak = rsa(1024);
        assertInvalid(persistent(weak, weak));
    }

    @Test
    void shouldRejectMalformedPemWithoutLeakingDecoderCauses() {
        ByteArrayResource invalid = new ByteArrayResource("sentinel-private-material!".getBytes());
        assertInvalid(new JwtSecurityProperties("https://flowpay.dev", Duration.ofMinutes(15), false, invalid, invalid));
    }

    @Test
    void shouldRejectUnreadableKeyResourcesWithoutLeakingTheirLocations() {
        FileSystemResource missing = new FileSystemResource("missing/sentinel-private-material/key.pem");
        assertInvalid(new JwtSecurityProperties("https://flowpay.dev", Duration.ofMinutes(15), false, missing, missing));
    }

    private void assertInvalid(JwtSecurityProperties properties) {
        assertThatIllegalStateException().isThrownBy(() -> configuration.jwtRsaKeyPair(properties))
                .withMessage("Configured JWT keys must be a readable matching RSA pair of at least 2048 bits")
                .withNoCause();
    }

    private static KeyPair rsa(int size) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(size);
        return generator.generateKeyPair();
    }

    private static JwtSecurityProperties persistent(KeyPair publicPair, KeyPair privatePair) {
        return new JwtSecurityProperties("https://flowpay.dev", Duration.ofMinutes(15), false,
                pem("PUBLIC KEY", publicPair.getPublic().getEncoded()),
                pem("PRIVATE KEY", privatePair.getPrivate().getEncoded()));
    }

    private static ByteArrayResource pem(String type, byte[] bytes) {
        return new ByteArrayResource(("-----BEGIN " + type + "-----\n"
                + Base64.getMimeEncoder().encodeToString(bytes) + "\n-----END " + type + "-----").getBytes());
    }

    private static JwtSecurityProperties properties(boolean ephemeral) {
        return new JwtSecurityProperties("https://flowpay.dev", Duration.ofMinutes(15), ephemeral, null, null);
    }
}
