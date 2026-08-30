package com.flowpay.backend.infrastructure.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

@ConfigurationProperties(prefix = "flowpay.security.jwt")
public record JwtSecurityProperties(
        String issuer,
        Duration accessTokenTtl,
        boolean generateEphemeralKeyPair,
        Resource publicKeyLocation,
        Resource privateKeyLocation
) {

    public JwtSecurityProperties {
        issuer = Objects.requireNonNull(issuer, "issuer must not be null").trim();
        if (issuer.isEmpty()) {
            throw new IllegalArgumentException("issuer must not be blank");
        }
        if (!URI.create(issuer).isAbsolute()) {
            throw new IllegalArgumentException("issuer must be an absolute URI");
        }
        Objects.requireNonNull(accessTokenTtl, "accessTokenTtl must not be null");
        if (accessTokenTtl.isZero() || accessTokenTtl.isNegative()) {
            throw new IllegalArgumentException("accessTokenTtl must be positive");
        }
    }
}
