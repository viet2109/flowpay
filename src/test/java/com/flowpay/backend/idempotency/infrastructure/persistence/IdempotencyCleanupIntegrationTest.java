package com.flowpay.backend.idempotency.infrastructure.persistence;

import com.flowpay.backend.idempotency.application.IdempotencyCleanupService;
import com.flowpay.backend.idempotency.application.IdempotencyProperties;
import com.flowpay.backend.testing.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "flowpay.idempotency.cleanup.enabled=false",
        "flowpay.idempotency.cleanup.batch-size=2",
        "flowpay.idempotency.cleanup.max-batches-per-run=10"
})
@ActiveProfiles("test")
class IdempotencyCleanupIntegrationTest extends PostgresIntegrationTest {

    private static final String REQUEST_HASH = "c".repeat(64);

    @Autowired
    private IdempotencyCleanupService cleanupService;

    @Autowired
    private IdempotencyProperties properties;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanData() {
        jdbcTemplate.update(
                "TRUNCATE TABLE idempotency_records, payment_transactions, "
                        + "payment_intents, merchants RESTART IDENTITY CASCADE"
        );
    }

    @Test
    void shouldDeleteOnlyExpiredCompletedRecordsAcrossMultipleBatches() {
        Instant now = Instant.now();
        long merchantId = insertMerchant(now);
        insertPayment(merchantId, now);

        for (int index = 1; index <= 5; index++) {
            insertRecord(
                    merchantId,
                    "expired-completed-" + index,
                    "COMPLETED",
                    now.minusSeconds(60)
            );
        }
        insertRecord(
                merchantId,
                "active-completed",
                "COMPLETED",
                now.plusSeconds(3_600)
        );
        insertRecord(
                merchantId,
                "expired-processing",
                "PROCESSING",
                now.minusSeconds(60)
        );
        insertRecord(
                merchantId,
                "active-processing",
                "PROCESSING",
                now.plusSeconds(3_600)
        );

        int deleted = cleanupService.cleanupExpiredCompleted();

        assertThat(deleted).isEqualTo(5);
        assertThat(jdbcTemplate.queryForList(
                "SELECT idempotency_key FROM idempotency_records ORDER BY idempotency_key",
                String.class
        )).containsExactly(
                "active-completed",
                "active-processing",
                "expired-processing"
        );
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_intents",
                Long.class
        )).isEqualTo(1L);
        assertThat(properties.retention())
                .isEqualTo(IdempotencyProperties.DEFAULT_RETENTION);
        assertThat(properties.cleanup().batchSize()).isEqualTo(2);
    }

    private long insertMerchant(Instant now) {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO merchants (public_id, name, status, created_at, updated_at, version)
                VALUES ('mrc_cleanup', 'Cleanup Merchant', 'ACTIVE', ?, ?, 0)
                RETURNING id
                """,
                Long.class,
                now.atOffset(ZoneOffset.UTC),
                now.atOffset(ZoneOffset.UTC)
        );
    }

    private void insertPayment(long merchantId, Instant now) {
        jdbcTemplate.update(
                """
                INSERT INTO payment_intents (
                    public_id,
                    merchant_id,
                    amount_minor,
                    currency,
                    status,
                    created_at,
                    updated_at,
                    version
                )
                VALUES ('pi_cleanup', ?, 1000, 'USD', 'CREATED', ?, ?, 0)
                """,
                merchantId,
                now.atOffset(ZoneOffset.UTC),
                now.atOffset(ZoneOffset.UTC)
        );
    }

    private void insertRecord(
            long merchantId,
            String key,
            String status,
            Instant expiresAt
    ) {
        boolean completed = status.equals("COMPLETED");
        Instant createdAt = expiresAt.minusSeconds(7_200);
        Instant completedAt = completed ? expiresAt.minusSeconds(3_600) : null;
        jdbcTemplate.update(
                """
                INSERT INTO idempotency_records (
                    merchant_id,
                    operation,
                    idempotency_key,
                    request_hash,
                    status,
                    resource_type,
                    resource_public_id,
                    http_status,
                    response_payload,
                    created_at,
                    completed_at,
                    expires_at
                )
                VALUES (?, 'PAYMENT_INTENT_CREATE', ?, ?, ?, ?, ?, ?, CAST(? AS JSONB), ?, ?, ?)
                """,
                merchantId,
                key,
                REQUEST_HASH,
                status,
                completed ? "PAYMENT_INTENT" : null,
                completed ? "pi_cleanup" : null,
                completed ? 201 : null,
                completed ? "{\"data\":{\"id\":\"pi_cleanup\"}}" : null,
                createdAt.atOffset(ZoneOffset.UTC),
                completedAt == null ? null : completedAt.atOffset(ZoneOffset.UTC),
                expiresAt.atOffset(ZoneOffset.UTC)
        );
    }
}
