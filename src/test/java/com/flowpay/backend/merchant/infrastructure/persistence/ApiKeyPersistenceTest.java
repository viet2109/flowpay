package com.flowpay.backend.merchant.infrastructure.persistence;

import com.flowpay.backend.merchant.application.ApiKeyPublicIdGenerator;
import com.flowpay.backend.merchant.application.ApiKeyRepository;
import com.flowpay.backend.merchant.application.ApiKeySecretCodec;
import com.flowpay.backend.merchant.application.GeneratedApiKeySecret;
import com.flowpay.backend.merchant.domain.ApiKey;
import com.flowpay.backend.merchant.domain.ApiKeyStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class ApiKeyPersistenceTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-30T03:00:00Z");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16.15-alpine");

    @Autowired
    private ApiKeyRepository apiKeyRepository;

    @Autowired
    private ApiKeyPublicIdGenerator publicIdGenerator;

    @Autowired
    private ApiKeySecretCodec secretCodec;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanApiKeyData() {
        jdbcTemplate.update("TRUNCATE TABLE merchant_api_keys, merchant_members, merchants RESTART IDENTITY CASCADE");
    }

    @Test
    void shouldPersistAndReloadWithoutPersistingRawKey() {
        long merchantId = insertMerchant("mrc_api_key_persistence");
        GeneratedApiKeySecret secret = secretCodec.generate();
        ApiKey saved = apiKeyRepository.save(ApiKey.create(
                publicIdGenerator.nextId(),
                merchantId,
                " Development backend ",
                secret.prefix(),
                secret.digest(),
                CREATED_AT.plusSeconds(86_400),
                CREATED_AT
        ));

        ApiKey reloaded = apiKeyRepository.findByPublicId(saved.publicId()).orElseThrow();
        ApiKey byPrefix = apiKeyRepository.findByKeyPrefix(secret.prefix()).orElseThrow();
        Map<String, Object> storedRow = jdbcTemplate.queryForMap(
                "SELECT * FROM merchant_api_keys WHERE id = ?",
                saved.id()
        );

        assertThat(saved.id()).isPositive();
        assertThat(reloaded.publicId()).isEqualTo(saved.publicId()).startsWith("key_");
        assertThat(reloaded.merchantId()).isEqualTo(merchantId);
        assertThat(reloaded.name()).isEqualTo("Development backend");
        assertThat(reloaded.keyPrefix()).isEqualTo(secret.prefix());
        assertThat(reloaded.keyHash()).isEqualTo(secret.digest());
        assertThat(reloaded.status()).isEqualTo(ApiKeyStatus.ACTIVE);
        assertThat(reloaded.createdAt()).isEqualTo(CREATED_AT);
        assertThat(byPrefix.publicId()).isEqualTo(saved.publicId());
        assertThat(storedRow).doesNotContainKeys("raw_key", "api_key", "key_secret");
        assertThat(storedRow.values()).noneMatch(secret.rawKey()::equals);
    }

    @Test
    void shouldPersistRevocationAndScopeQueriesToMerchant() {
        long owningMerchantId = insertMerchant("mrc_api_key_owner");
        long otherMerchantId = insertMerchant("mrc_api_key_other");
        GeneratedApiKeySecret secret = secretCodec.generate();
        ApiKey saved = apiKeyRepository.save(ApiKey.create(
                publicIdGenerator.nextId(),
                owningMerchantId,
                "Production backend",
                secret.prefix(),
                secret.digest(),
                null,
                CREATED_AT
        ));

        saved.recordLastUsed(CREATED_AT.plusSeconds(30));
        saved.revoke(CREATED_AT.plusSeconds(60));
        ApiKey revoked = apiKeyRepository.save(saved);
        ApiKey reloaded = apiKeyRepository.findByPublicIdAndMerchantId(
                saved.publicId(),
                owningMerchantId
        ).orElseThrow();

        assertThat(revoked.status()).isEqualTo(ApiKeyStatus.REVOKED);
        assertThat(reloaded.status()).isEqualTo(ApiKeyStatus.REVOKED);
        assertThat(reloaded.lastUsedAt()).isEqualTo(CREATED_AT.plusSeconds(30));
        assertThat(reloaded.revokedAt()).isEqualTo(CREATED_AT.plusSeconds(60));
        assertThat(apiKeyRepository.findAllByMerchantId(owningMerchantId))
                .extracting(ApiKey::publicId)
                .containsExactly(saved.publicId());
        assertThat(apiKeyRepository.findByPublicIdAndMerchantId(saved.publicId(), otherMerchantId)).isEmpty();
        assertThat(apiKeyRepository.findAllByMerchantId(otherMerchantId)).isEmpty();
    }

    private long insertMerchant(String publicId) {
        Long id = jdbcTemplate.queryForObject(
                """
                INSERT INTO merchants (public_id, name, status, created_at, updated_at, version)
                VALUES (?, ?, 'ACTIVE', ?, ?, 0)
                RETURNING id
                """,
                Long.class,
                publicId,
                "API Key Store",
                CREATED_AT.atOffset(ZoneOffset.UTC),
                CREATED_AT.atOffset(ZoneOffset.UTC)
        );
        return id;
    }
}
