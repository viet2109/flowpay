package com.flowpay.backend.payment.application;

import com.flowpay.backend.common.security.MerchantApiPrincipal;
import com.flowpay.backend.idempotency.application.CreatePaymentFingerprint;
import com.flowpay.backend.idempotency.application.IdempotencyAcquisitionCommand;
import com.flowpay.backend.idempotency.application.IdempotencyAcquisitionDecision;
import com.flowpay.backend.idempotency.application.IdempotencyAcquisitionService;
import com.flowpay.backend.idempotency.application.IdempotencyKeyReusedException;
import com.flowpay.backend.idempotency.application.IdempotencyRequestInProgressException;
import com.flowpay.backend.idempotency.application.RequestFingerprintService;
import com.flowpay.backend.idempotency.domain.IdempotencyKey;
import com.flowpay.backend.idempotency.domain.IdempotencyOperation;
import com.flowpay.backend.idempotency.domain.IdempotencyRecord;
import com.flowpay.backend.idempotency.domain.IdempotencyRepository;
import com.flowpay.backend.testing.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class IdempotentCreatePaymentIntegrationTest extends PostgresIntegrationTest {

    private static final Instant MERCHANT_CREATED_AT = Instant.parse("2026-08-31T08:00:00Z");

    @Autowired
    private IdempotentCreatePaymentService service;

    @Autowired
    private IdempotencyAcquisitionService acquisitionService;

    @Autowired
    private RequestFingerprintService fingerprintService;

    @Autowired
    private IdempotencyRepository idempotencyRepository;

    @Autowired
    private CreatePaymentResponseSnapshotCodec snapshotCodec;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanData() {
        dropFailureTrigger();
        jdbcTemplate.update("""
                TRUNCATE TABLE idempotency_records, payment_transactions, payment_intents,
                    merchant_members, merchant_api_keys, refresh_tokens, merchants, users
                    RESTART IDENTITY CASCADE
                """);
    }

    @AfterEach
    void removeFailureTrigger() {
        dropFailureTrigger();
    }

    @Test
    void newRequestShouldCompleteIdempotencyAndReplayStoredResponseWithoutSecondPayment() {
        long merchantId = insertMerchant("mrc_create_replay");
        IdempotentCreatePaymentCommand command = command(
                "mrc_create_replay",
                "checkout-replay",
                50_000L
        );

        IdempotentCreatePaymentResult created = service.create(command);
        IdempotentCreatePaymentResult replayed = service.create(command);

        assertThat(created.replayed()).isFalse();
        assertThat(replayed.replayed()).isTrue();
        assertThat(replayed.httpStatus()).isEqualTo(201);
        assertThat(replayed.response()).isEqualTo(created.response());
        assertThat(countPayments(merchantId)).isEqualTo(1);
        assertThat(countIdempotencyRows(merchantId, "checkout-replay")).isEqualTo(1);

        IdempotencyRecord record = idempotencyRepository.findByScope(
                merchantId,
                IdempotencyOperation.PAYMENT_INTENT_CREATE,
                IdempotencyKey.of("checkout-replay")
        ).orElseThrow();
        assertThat(record.isCompleted()).isTrue();
        assertThat(record.resourceType()).isEqualTo("PAYMENT_INTENT");
        assertThat(record.resourcePublicId()).isEqualTo(created.response().id());
        assertThat(record.httpStatus()).isEqualTo(201);
        assertThat(snapshotCodec.decode(record.responsePayload())).isEqualTo(created.response());
        assertThat(Duration.between(record.completedAt(), record.expiresAt()))
                .isEqualTo(Duration.ofHours(24));
    }

    @Test
    void differentRequestWithSameKeyShouldBeRejectedWithoutSecondPayment() {
        long merchantId = insertMerchant("mrc_create_reused");
        service.create(command("mrc_create_reused", "checkout-reused", 50_000L));

        assertThatThrownBy(() -> service.create(command(
                "mrc_create_reused",
                "checkout-reused",
                50_001L
        ))).isInstanceOf(IdempotencyKeyReusedException.class);

        assertThat(countPayments(merchantId)).isEqualTo(1);
        assertThat(countIdempotencyRows(merchantId, "checkout-reused")).isEqualTo(1);
    }

    @Test
    void processingRequestShouldBeRejectedWithoutCreatingPayment() {
        long merchantId = insertMerchant("mrc_create_processing");
        IdempotentCreatePaymentCommand command = command(
                "mrc_create_processing",
                "checkout-processing",
                50_000L
        );
        String requestHash = fingerprintService.fingerprint(CreatePaymentFingerprint.version1(
                command.amountMinor(),
                command.currency(),
                command.orderId(),
                command.description()
        ));
        assertThat(acquisitionService.acquire(new IdempotencyAcquisitionCommand(
                merchantId,
                IdempotencyOperation.PAYMENT_INTENT_CREATE,
                command.idempotencyKey(),
                requestHash
        )).decision()).isEqualTo(IdempotencyAcquisitionDecision.NEW);

        assertThatThrownBy(() -> service.create(command))
                .isInstanceOf(IdempotencyRequestInProgressException.class);

        assertThat(countPayments(merchantId)).isZero();
        assertThat(countIdempotencyRows(merchantId, "checkout-processing")).isEqualTo(1);
    }

    @Test
    void concurrentSameRequestShouldCreateOnePaymentAndReplayToTheOtherCaller()
            throws Exception {
        long merchantId = insertMerchant("mrc_create_concurrent");
        IdempotentCreatePaymentCommand command = command(
                "mrc_create_concurrent",
                "checkout-concurrent",
                50_000L
        );
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<IdempotentCreatePaymentResult> first = executor.submit(
                    () -> createAfterSignal(command, ready, start)
            );
            Future<IdempotentCreatePaymentResult> second = executor.submit(
                    () -> createAfterSignal(command, ready, start)
            );
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<IdempotentCreatePaymentResult> results = List.of(
                    first.get(30, TimeUnit.SECONDS),
                    second.get(30, TimeUnit.SECONDS)
            );
            assertThat(results).extracting(IdempotentCreatePaymentResult::replayed)
                    .containsExactlyInAnyOrder(false, true);
            assertThat(results).extracting(result -> result.response().id()).containsOnly(
                    results.getFirst().response().id()
            );
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(countPayments(merchantId)).isEqualTo(1);
        assertThat(countIdempotencyRows(merchantId, "checkout-concurrent")).isEqualTo(1);
    }

    @Test
    void sameTextualKeyShouldBeIndependentAcrossMerchants() {
        long firstMerchantId = insertMerchant("mrc_create_scope_first");
        long secondMerchantId = insertMerchant("mrc_create_scope_second");

        IdempotentCreatePaymentResult first = service.create(command(
                "mrc_create_scope_first",
                "shared-checkout",
                50_000L
        ));
        IdempotentCreatePaymentResult second = service.create(command(
                "mrc_create_scope_second",
                "shared-checkout",
                50_000L
        ));

        assertThat(first.replayed()).isFalse();
        assertThat(second.replayed()).isFalse();
        assertThat(first.response().id()).isNotEqualTo(second.response().id());
        assertThat(countPayments(firstMerchantId)).isEqualTo(1);
        assertThat(countPayments(secondMerchantId)).isEqualTo(1);
        assertThat(countIdempotencyRows(firstMerchantId, "shared-checkout")).isEqualTo(1);
        assertThat(countIdempotencyRows(secondMerchantId, "shared-checkout")).isEqualTo(1);
    }

    @Test
    void paymentInsertFailureShouldRollbackTheNewIdempotencyRecord() {
        long merchantId = insertMerchant("mrc_create_rollback");
        installPaymentInsertFailureTrigger();

        assertThatThrownBy(() -> service.create(command(
                "mrc_create_rollback",
                "checkout-rollback",
                50_000L
        ))).isInstanceOf(RuntimeException.class);

        assertThat(countPayments(merchantId)).isZero();
        assertThat(countIdempotencyRows(merchantId, "checkout-rollback")).isZero();
    }

    private IdempotentCreatePaymentResult createAfterSignal(
            IdempotentCreatePaymentCommand command,
            CountDownLatch ready,
            CountDownLatch start
    ) throws InterruptedException {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("concurrent create did not start in time");
        }
        return service.create(command);
    }

    private IdempotentCreatePaymentCommand command(
            String merchantPublicId,
            String idempotencyKey,
            long amountMinor
    ) {
        return new IdempotentCreatePaymentCommand(
                new MerchantApiPrincipal(merchantPublicId, "key_" + merchantPublicId),
                IdempotencyKey.of(idempotencyKey),
                amountMinor,
                "VND",
                "ORDER-1001",
                "Payment for ORDER-1001"
        );
    }

    private int countPayments(long merchantId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM payment_intents WHERE merchant_id = ?",
                Integer.class,
                merchantId
        );
    }

    private int countIdempotencyRows(long merchantId, String key) {
        return jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM idempotency_records
                WHERE merchant_id = ?
                  AND operation = 'PAYMENT_INTENT_CREATE'
                  AND idempotency_key = ?
                """,
                Integer.class,
                merchantId,
                key
        );
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
                "Idempotent Create Store",
                MERCHANT_CREATED_AT.atOffset(ZoneOffset.UTC),
                MERCHANT_CREATED_AT.atOffset(ZoneOffset.UTC)
        );
        return id;
    }

    private void installPaymentInsertFailureTrigger() {
        jdbcTemplate.execute("""
                CREATE FUNCTION fail_idempotent_payment_insert()
                RETURNS trigger
                LANGUAGE plpgsql
                AS $$
                BEGIN
                    RAISE EXCEPTION 'forced payment insert failure';
                END;
                $$
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER fail_idempotent_payment_insert_trigger
                BEFORE INSERT ON payment_intents
                FOR EACH ROW
                EXECUTE FUNCTION fail_idempotent_payment_insert()
                """);
    }

    private void dropFailureTrigger() {
        jdbcTemplate.execute("""
                DROP TRIGGER IF EXISTS fail_idempotent_payment_insert_trigger
                ON payment_intents
                """);
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS fail_idempotent_payment_insert() ");
    }
}
