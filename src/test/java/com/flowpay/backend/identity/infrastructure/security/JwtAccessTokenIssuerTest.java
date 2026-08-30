package com.flowpay.backend.identity.infrastructure.security;

import com.flowpay.backend.identity.application.AuthenticatedIdentity;
import com.flowpay.backend.identity.application.IssuedAccessToken;
import com.flowpay.backend.infrastructure.security.JwtSecurityProperties;
import com.flowpay.backend.merchant.domain.MerchantRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtAccessTokenIssuerTest {

    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    private static final AuthenticatedIdentity IDENTITY = new AuthenticatedIdentity(
            "usr_01KPUBLIC",
            "viet@example.com",
            "mrc_01KPUBLIC",
            MerchantRole.OWNER
    );

    private NimbusJwtEncoder encoder;
    private NimbusJwtDecoder decoder;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();
        RSAPrivateKey privateKey = (RSAPrivateKey) keyPair.getPrivate();

        encoder = NimbusJwtEncoder.withKeyPair(publicKey, privateKey)
                .algorithm(SignatureAlgorithm.RS256)
                .build();
        decoder = NimbusJwtDecoder.withPublicKey(publicKey)
                .signatureAlgorithm(SignatureAlgorithm.RS256)
                .build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer("https://flowpay.dev"));
    }

    @Test
    void shouldIssueValidRsaJwtWithExpectedPublicClaims() {
        JwtAccessTokenIssuer issuer = issuer(Duration.ofMinutes(15), NOW);

        IssuedAccessToken issued = issuer.issue(IDENTITY);
        Jwt decoded = decoder.decode(issued.value());

        assertThat(decoded.getSubject()).isEqualTo("usr_01KPUBLIC");
        assertThat(decoded.getIssuer().toString()).isEqualTo("https://flowpay.dev");
        assertThat(decoded.getClaimAsString("merchant")).isEqualTo("mrc_01KPUBLIC");
        assertThat(decoded.getClaimAsString("role")).isEqualTo("OWNER");
        assertThat(decoded.getId()).isNotBlank();
        assertThat(decoded.getIssuedAt()).isEqualTo(NOW);
        assertThat(decoded.getExpiresAt()).isEqualTo(NOW.plusSeconds(900));
        assertThat(issued.expiresInSeconds()).isEqualTo(900);
        assertThat(decoded.getClaims()).doesNotContainKeys("userId", "merchantId", "password", "passwordHash");
        assertThat(issued.toString()).doesNotContain(issued.value()).contains("[REDACTED]");
    }

    @Test
    void shouldUseConfiguredAccessTokenTtl() {
        JwtAccessTokenIssuer issuer = issuer(Duration.ofSeconds(37), NOW);

        IssuedAccessToken issued = issuer.issue(IDENTITY);

        assertThat(issued.expiresInSeconds()).isEqualTo(37);
        assertThat(issued.expiresAt()).isEqualTo(NOW.plusSeconds(37));
    }

    @Test
    void shouldRejectExpiredToken() {
        JwtAccessTokenIssuer issuer = issuer(Duration.ofSeconds(30), NOW.minus(Duration.ofHours(2)));
        IssuedAccessToken issued = issuer.issue(IDENTITY);

        assertThatThrownBy(() -> decoder.decode(issued.value())).isInstanceOf(JwtException.class);
    }

    @Test
    void shouldRejectMalformedToken() {
        assertThatThrownBy(() -> decoder.decode("not-a-jwt")).isInstanceOf(JwtException.class);
    }

    private JwtAccessTokenIssuer issuer(Duration ttl, Instant now) {
        JwtSecurityProperties properties = new JwtSecurityProperties(
                "https://flowpay.dev",
                ttl,
                true,
                null,
                null
        );
        return new JwtAccessTokenIssuer(encoder, properties, Clock.fixed(now, ZoneOffset.UTC));
    }
}
