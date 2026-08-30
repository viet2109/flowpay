package com.flowpay.backend.identity.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class RefreshTokenTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-30T01:00:00Z");
    private static final String TOKEN_HASH = "a".repeat(64);

    @Test
    void shouldRotateActiveTokenToPersistedReplacement() {
        RefreshToken token = persistedToken();
        Instant usedAt = CREATED_AT.plusSeconds(60);

        token.rotateTo(22L, usedAt);

        assertThat(token.isRevoked()).isTrue();
        assertThat(token.revokedAt()).isEqualTo(usedAt);
        assertThat(token.lastUsedAt()).isEqualTo(usedAt);
        assertThat(token.replacedById()).isEqualTo(22L);
    }

    @Test
    void shouldRejectRotationOfRevokedToken() {
        RefreshToken token = persistedToken();
        token.revoke(CREATED_AT.plusSeconds(10));

        assertThatIllegalStateException()
                .isThrownBy(() -> token.rotateTo(22L, CREATED_AT.plusSeconds(20)))
                .withMessage("Refresh token is revoked");
    }

    @Test
    void shouldRejectRotationOfExpiredToken() {
        RefreshToken token = persistedToken();

        assertThat(token.isExpired(CREATED_AT.plusSeconds(300))).isTrue();
        assertThatIllegalStateException()
                .isThrownBy(() -> token.rotateTo(22L, CREATED_AT.plusSeconds(300)))
                .withMessage("Refresh token is expired");
    }

    private static RefreshToken persistedToken() {
        return RefreshToken.rehydrate(
                10L,
                11L,
                TOKEN_HASH,
                CREATED_AT.plusSeconds(300),
                null,
                CREATED_AT,
                null,
                null
        );
    }
}
