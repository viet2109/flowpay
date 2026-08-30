package com.flowpay.backend.infrastructure.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

@Configuration
class JwtKeyConfiguration {

    private static final int RSA_KEY_SIZE = 2048;

    @Bean
    JwtRsaKeyPair jwtRsaKeyPair(JwtSecurityProperties properties) {
        if (properties.generateEphemeralKeyPair()) {
            return generateKeyPair();
        }
        if (properties.publicKeyLocation() == null || properties.privateKeyLocation() == null) {
            throw new IllegalStateException("JWT public and private key locations must be configured");
        }
        return loadKeyPair(properties.publicKeyLocation(), properties.privateKeyLocation());
    }

    @Bean
    JwtEncoder jwtEncoder(JwtRsaKeyPair keyPair) {
        return NimbusJwtEncoder.withKeyPair(keyPair.publicKey(), keyPair.privateKey())
                .algorithm(SignatureAlgorithm.RS256)
                .build();
    }

    @Bean
    JwtDecoder jwtDecoder(JwtRsaKeyPair keyPair, JwtSecurityProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(keyPair.publicKey())
                .signatureAlgorithm(SignatureAlgorithm.RS256)
                .build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.issuer()));
        return decoder;
    }

    private static JwtRsaKeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(RSA_KEY_SIZE);
            KeyPair generated = generator.generateKeyPair();
            return new JwtRsaKeyPair(
                    (RSAPublicKey) generated.getPublic(),
                    (RSAPrivateKey) generated.getPrivate()
            );
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Could not generate ephemeral JWT key pair", exception);
        }
    }

    private static JwtRsaKeyPair loadKeyPair(Resource publicKeyResource, Resource privateKeyResource) {
        try {
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            RSAPublicKey publicKey = (RSAPublicKey) keyFactory.generatePublic(
                    new X509EncodedKeySpec(readPem(publicKeyResource, "PUBLIC KEY"))
            );
            RSAPrivateKey privateKey = (RSAPrivateKey) keyFactory.generatePrivate(
                    new PKCS8EncodedKeySpec(readPem(privateKeyResource, "PRIVATE KEY"))
            );
            return new JwtRsaKeyPair(publicKey, privateKey);
        } catch (GeneralSecurityException | IOException exception) {
            throw new IllegalStateException("Could not load configured JWT key pair", exception);
        }
    }

    private static byte[] readPem(Resource resource, String type) throws IOException {
        String pem;
        try (InputStream input = resource.getInputStream()) {
            pem = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.US_ASCII);
        }
        String encoded = pem
                .replace("-----BEGIN " + type + "-----", "")
                .replace("-----END " + type + "-----", "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(encoded);
    }
}
