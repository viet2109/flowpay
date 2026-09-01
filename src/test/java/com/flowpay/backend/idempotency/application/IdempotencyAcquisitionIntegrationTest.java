package com.flowpay.backend.idempotency.application;

import com.flowpay.backend.idempotency.domain.IdempotencyKey;
import com.flowpay.backend.idempotency.domain.IdempotencyOperation;
import com.flowpay.backend.idempotency.domain.IdempotencyRecord;
import com.flowpay.backend.idempotency.domain.IdempotencyRepository;
import com.flowpay.backend.testing.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class IdempotencyAcquisitionIntegrationTest extends PostgresIntegrationTest {

    private static final Instant MERCHANT_CREATED_AT = Instant.parse("2026-08-31T08:00:00Z");
    private static final String REQUEST_HASH = "a".repeat(64);
    private static final String OTHER_HASH = "b".repeat(64);

    @Autowired
    private IdempotencyAcquisitionService acquisitionService;

    @Autowired
    private IdempotencyRepository idempotencyRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanData() {
        jdbcTemplate.update(
                "TRUNCATE TABLE idempotency_records, merchants RESTART IDENTITY CASCADE"
        );
    }

    @Test
    void firstAcquisitionShouldBeNewAndSameProcessingRequestShouldBeInProgress() {
        long merchantId = insertMerchant("mrc_acquire_first");
        IdempotencyAcquisitionCommand command = command(
                merchantId,
                IdempotencyOperation.PAYMENT_INTENT_CREATE,
                "checkout-first",
                REQUEST_HASH
        );

        IdempotencyAcquisitionResult first = acquisitionService.acquire(command);
        IdempotencyAcquisitionResult duplicate = acquisitionService.acquire(command);

        assertThat(first.decision()).isEqualTo(IdempotencyAcquisitionDecision.NEW);
        assertThat(first.executionId()).isPositive();
        IdempotencyRecord persisted = idempotencyRepository.findByInternalId(
                first.executionId()
        ).orElseThrow();
        assertThat(persisted.isProcessing()).isTrue();
        assertThat(Duration.between(persisted.createdAt(), persisted.expiresAt()))
                .isEqualTo(Duration.ofHours(24));
        assertThat(duplicate.decision())
                .isEqualTo(IdempotencyAcquisitionDecision.IN_PROGRESS);
        assertThat(duplicate.executionId()).isNull();
        assertThat(countScopedRows(
                merchantId,
                IdempotencyOperation.PAYMENT_INTENT_CREATE,
                "checkout-first"
        )).isEqualTo(1);
    }

    @Test
    void completedSameRequestShouldReplayPersistedRecord() {
        long merchantId = insertMerchant("mrc_acquire_replay");
        IdempotencyAcquisitionCommand command = command(
                merchantId,
                IdempotencyOperation.PAYMENT_INTENT_CREATE,
                "checkout-replay",
                REQUEST_HASH
        );
        long executionId = acquisitionService.acquire(command).executionId();
        IdempotencyRecord record = idempotencyRepository.findByInternalId(
                executionId
        ).orElseThrow();
        Instant completedAt = record.createdAt().plusSeconds(1);
        record.complete(
                "PAYMENT_INTENT",
                "pi_replay",
                201,
                "{\"data\":{\"id\":\"pi_replay\"}}",
                completedAt,
                completedAt.plusSeconds(86_400)
        );
        idempotencyRepository.save(record);

        IdempotencyAcquisitionResult replay = acquisitionService.acquire(command);

        assertThat(replay.decision()).isEqualTo(IdempotencyAcquisitionDecision.REPLAY);
        assertThat(replay.replayResponse().resourcePublicId()).isEqualTo("pi_replay");
        assertThat(replay.replayResponse().httpStatus()).isEqualTo(201);
        assertThat(replay.replayResponse().responsePayload()).contains("pi_replay");
    }

    @Test
    void differentRequestHashShouldReuseNeitherProcessingNorCompletedKey() {
        long merchantId = insertMerchant("mrc_acquire_reused");
        acquisitionService.acquire(command(
                merchantId,
                IdempotencyOperation.PAYMENT_INTENT_CREATE,
                "checkout-reused",
                REQUEST_HASH
        ));

        IdempotencyAcquisitionResult reused = acquisitionService.acquire(command(
                merchantId,
                IdempotencyOperation.PAYMENT_INTENT_CREATE,
                "checkout-reused",
                OTHER_HASH
        ));

        assertThat(reused.decision()).isEqualTo(IdempotencyAcquisitionDecision.KEY_REUSED);
        assertThat(countScopedRows(
                merchantId,
                IdempotencyOperation.PAYMENT_INTENT_CREATE,
                "checkout-reused"
        )).isEqualTo(1);
    }

    @Test
    void sameTextualKeyShouldBeIndependentAcrossMerchantAndAllOperationScopes() {
        long merchantId = insertMerchant("mrc_acquire_scope");
        long otherMerchantId = insertMerchant("mrc_acquire_scope_other");

        List<IdempotencyAcquisitionDecision> decisions = List.of(
                acquisitionService.acquire(command(
                        merchantId,
                        IdempotencyOperation.PAYMENT_INTENT_CREATE,
                        "shared-key",
                        REQUEST_HASH
                )).decision(),
                acquisitionService.acquire(command(
                        merchantId,
                        IdempotencyOperation.PAYMENT_INTENT_CONFIRM,
                        "shared-key",
                        REQUEST_HASH
                )).decision(),
                acquisitionService.acquire(command(
                        merchantId,
                        IdempotencyOperation.REFUND_CREATE,
                        "shared-key",
                        REQUEST_HASH
                )).decision(),
                acquisitionService.acquire(command(
                        otherMerchantId,
                        IdempotencyOperation.PAYMENT_INTENT_CREATE,
                        "shared-key",
                        REQUEST_HASH
                )).decision(),
                acquisitionService.acquire(command(
                        otherMerchantId,
                        IdempotencyOperation.REFUND_CREATE,
                        "shared-key",
                        REQUEST_HASH
                )).decision()
        );

        assertThat(decisions).containsExactly(
                IdempotencyAcquisitionDecision.NEW,
                IdempotencyAcquisitionDecision.NEW,
                IdempotencyAcquisitionDecision.NEW,
                IdempotencyAcquisitionDecision.NEW,
                IdempotencyAcquisitionDecision.NEW
        );
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM idempotency_records WHERE idempotency_key = ?",
                Integer.class,
                "shared-key"
        )).isEqualTo(5);
    }

    @Test
    void concurrentFirstAcquisitionsShouldProduceOneNewOwnerAndOneRow() throws Exception {
        long merchantId = insertMerchant("mrc_acquire_concurrent");
        IdempotencyAcquisitionCommand command = command(
                merchantId,
                IdempotencyOperation.PAYMENT_INTENT_CREATE,
                "checkout-concurrent",
                REQUEST_HASH
        );
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<IdempotencyAcquisitionResult> first = executor.submit(
                    () -> acquireAfterSignal(command, ready, start)
            );
            Future<IdempotencyAcquisitionResult> second = executor.submit(
                    () -> acquireAfterSignal(command, ready, start)
            );
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(List.of(first.get(20, TimeUnit.SECONDS).decision(),
                    second.get(20, TimeUnit.SECONDS).decision()))
                    .containsExactlyInAnyOrder(
                            IdempotencyAcquisitionDecision.NEW,
                            IdempotencyAcquisitionDecision.IN_PROGRESS
                    );
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(countScopedRows(
                merchantId,
                IdempotencyOperation.PAYMENT_INTENT_CREATE,
                "checkout-concurrent"
        )).isEqualTo(1);
    }

    private IdempotencyAcquisitionResult acquireAfterSignal(
            IdempotencyAcquisitionCommand command,
            CountDownLatch ready,
            CountDownLatch start
    ) throws InterruptedException {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("concurrent acquisition did not start in time");
        }
        return acquisitionService.acquire(command);
    }

    private IdempotencyAcquisitionCommand command(
            long merchantId,
            IdempotencyOperation operation,
            String key,
            String requestHash
    ) {
        return new IdempotencyAcquisitionCommand(
                merchantId,
                operation,
                IdempotencyKey.of(key),
                requestHash
        );
    }

    private int countScopedRows(
            long merchantId,
            IdempotencyOperation operation,
            String key
    ) {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM idempotency_records
                WHERE merchant_id = ? AND operation = ? AND idempotency_key = ?
                """,
                Integer.class,
                merchantId,
                operation.name(),
                key
        );
        return count == null ? 0 : count;
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
                "Acquisition Store",
                MERCHANT_CREATED_AT.atOffset(ZoneOffset.UTC),
                MERCHANT_CREATED_AT.atOffset(ZoneOffset.UTC)
        );
        return id;
    }
}
