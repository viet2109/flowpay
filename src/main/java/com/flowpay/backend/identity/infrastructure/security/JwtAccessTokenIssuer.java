package com.flowpay.backend.identity.infrastructure.security;

import com.flowpay.backend.identity.application.AccessTokenIssuer;
import com.flowpay.backend.identity.application.AuthenticatedIdentity;
import com.flowpay.backend.identity.application.IssuedAccessToken;
import com.flowpay.backend.infrastructure.security.JwtSecurityProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class JwtAccessTokenIssuer implements AccessTokenIssuer {

    private final JwtEncoder encoder;
    private final JwtSecurityProperties properties;
    private final Clock clock;

    @Override
    public IssuedAccessToken issue(AuthenticatedIdentity identity) {
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plus(properties.accessTokenTtl());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .id(UUID.randomUUID().toString())
                .subject(identity.userPublicId())
                .claim("merchant", identity.merchantPublicId())
                .claim("role", identity.role().name())
                .build();

        Jwt jwt = encoder.encode(JwtEncoderParameters.from(claims));
        return new IssuedAccessToken(jwt.getTokenValue(), properties.accessTokenTtl().toSeconds(), expiresAt);
    }
}
