package com.flowpay.backend.merchant.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiKeyTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-30T02:00:00Z");
    private static final String KEY_HASH = "a".repeat(64);
    private static final String KEY_PREFIX = "fp_test_A7x9Lm2Pq4Rs";

    @Test
    void shouldCreateActiveApiKeyWithoutRawSecret() {
        ApiKey apiKey = newApiKey(null);

        assertThat(apiKey.id()).isNull();
        assertThat(apiKey.publicId()).isEqualTo("key_test");
        assertThat(apiKey.merchantId()).isEqualTo(41L);
        assertThat(apiKey.name()).isEqualTo("Development backend");
        assertThat(apiKey.keyPrefix()).isEqualTo(KEY_PREFIX);
        assertThat(apiKey.keyHash()).isEqualTo(KEY_HASH);
        assertThat(apiKey.status()).isEqualTo(ApiKeyStatus.ACTIVE);
        assertThat(apiKey.lastUsedAt()).isNull();
        assertThat(apiKey.revokedAt()).isNull();
        assertThat(apiKey.createdAt()).isEqualTo(CREATED_AT);
    }

    @Test
    void shouldRevokeActiveApiKey() {
        ApiKey apiKey = newApiKey(null);
        Instant revokedAt = CREATED_AT.plusSeconds(60);

        apiKey.revoke(revokedAt);

        assertThat(apiKey.status()).isEqualTo(ApiKeyStatus.REVOKED);
        assertThat(apiKey.revokedAt()).isEqualTo(revokedAt);
        assertThatThrownBy(() -> apiKey.requireActiveAt(revokedAt.plusSeconds(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("API key is revoked");
        assertThatThrownBy(() -> apiKey.revoke(revokedAt.plusSeconds(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("API key is already revoked");
    }

    @Test
    void shouldRecordMonotonicLastUseOnlyWhileActive() {
        ApiKey apiKey = newApiKey(null);
        Instant firstUse = CREATED_AT.plusSeconds(30);

        apiKey.recordLastUsed(firstUse);

        assertThat(apiKey.lastUsedAt()).isEqualTo(firstUse);
        assertThatThrownBy(() -> apiKey.recordLastUsed(firstUse.minusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("usedAt must not be before lastUsedAt");
    }

    @Test
    void shouldRejectUseAtOrAfterExpiry() {
        Instant expiresAt = CREATED_AT.plusSeconds(120);
        ApiKey apiKey = newApiKey(expiresAt);

        assertThat(apiKey.isExpiredAt(expiresAt.minusNanos(1))).isFalse();
        assertThat(apiKey.isExpiredAt(expiresAt)).isTrue();
        assertThatThrownBy(() -> apiKey.recordLastUsed(expiresAt))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("API key is expired");
    }

    @Test
    void shouldRejectInvalidPersistenceState() {
        assertThatThrownBy(() -> ApiKey.rehydrate(
                1L,
                "key_invalid_state",
                41L,
                "Backend",
                KEY_PREFIX,
                KEY_HASH,
                ApiKeyStatus.REVOKED,
                null,
                null,
                null,
                CREATED_AT
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a revoked API key must have revokedAt");
    }

    private static ApiKey newApiKey(Instant expiresAt) {
        return ApiKey.create(
                "key_test",
                41L,
                " Development backend ",
                KEY_PREFIX,
                KEY_HASH,
                expiresAt,
                CREATED_AT
        );
    }
}
