package com.flowpay.backend.payment.application;

import com.flowpay.backend.common.error.ApiException;
import com.flowpay.backend.common.error.ErrorCode;
import com.flowpay.backend.common.money.Money;
import com.flowpay.backend.common.security.MerchantApiPrincipal;
import com.flowpay.backend.idempotency.application.ConfirmPaymentFingerprint;
import com.flowpay.backend.idempotency.application.IdempotencyAcquisitionCommand;
import com.flowpay.backend.idempotency.application.IdempotencyAcquisitionResult;
import com.flowpay.backend.idempotency.application.IdempotencyAcquisitionService;
import com.flowpay.backend.idempotency.application.IdempotencyKeyReusedException;
import com.flowpay.backend.idempotency.application.IdempotencyRequestInProgressException;
import com.flowpay.backend.idempotency.application.RequestFingerprintService;
import com.flowpay.backend.idempotency.domain.IdempotencyKey;
import com.flowpay.backend.idempotency.domain.IdempotencyOperation;
import com.flowpay.backend.idempotency.domain.IdempotencyRecord;
import com.flowpay.backend.idempotency.domain.IdempotencyRepository;
import com.flowpay.backend.idempotency.domain.IdempotencyStatus;
import com.flowpay.backend.payment.domain.PaymentIntent;
import com.flowpay.backend.payment.domain.PaymentStatus;
import com.flowpay.backend.payment.domain.PaymentTransactionStatus;
import com.flowpay.backend.payment.domain.ProviderOutcome;
import com.flowpay.backend.testing.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(IdempotentConfirmPaymentIntegrationTest.ProviderTestConfiguration.class)
class IdempotentConfirmPaymentIntegrationTest extends PostgresIntegrationTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-31T11:00:00Z");
    private static final String PAYMENT_RESOURCE = "PAYMENT_INTENT";

    @Autowired
    private IdempotentConfirmPaymentService service;

    @Autowired
    private IdempotencyAcquisitionService acquisitionService;

    @Autowired
    private IdempotencyRepository idempotencyRepository;

    @Autowired
    private RequestFingerprintService fingerprintService;

    @Autowired
    private ConfirmPaymentResponseSnapshotCodec snapshotCodec;

    @Autowired
    private PaymentIntentRepository paymentIntentRepository;

    @Autowired
    private PaymentTransactionRepository paymentTransactionRepository;

    @Autowired
    private InspectingConfirmProvider paymentProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanData() {
        dropTx1FailureTrigger();
        jdbcTemplate.update("""
                TRUNCATE TABLE outbox_events, ledger_entries, ledger_transactions,
                    ledger_accounts, idempotency_records, payment_transactions,
                    payment_intents, refresh_tokens, merchant_api_keys, merchant_members,
                    merchants, users RESTART IDENTITY CASCADE
                """);
        paymentProvider.reset();
    }

    @AfterEach
    void cleanTrigger() {
        dropTx1FailureTrigger();
    }

    @ParameterizedTest
    @EnumSource(ProviderOutcome.class)
    void confirmShouldPersistReplayableSnapshotForEveryProviderOutcome(
            ProviderOutcome outcome
    ) {
        String suffix = outcome.name().toLowerCase();
        PaymentFixture fixture = createPayment("snapshot_" + suffix);
        paymentProvider.respondWith(outcome);

        IdempotentConfirmPaymentResult result = service.confirm(command(
                fixture,
                "confirm-snapshot-" + suffix
        ));

        assertThat(result.replayed()).isFalse();
        assertThat(result.response().paymentStatus()).isEqualTo(paymentStatus(outcome));
        assertThat(result.response().transactionStatus())
                .isEqualTo(transactionStatus(outcome));
        assertThat(result.response().provider()).isEqualTo("SIMULATOR");
        assertThat(result.response().failureCode())
                .isEqualTo(paymentProvider.resultFor(outcome).failureCode());
        assertThat(result.response().failureMessage())
                .isEqualTo(paymentProvider.resultFor(outcome).failureMessage());
        assertThat(result.httpStatus()).isEqualTo(
                outcome == ProviderOutcome.UNKNOWN ? 202 : 200
        );
        assertThat(paymentProvider.invocationCount()).isEqualTo(1);
        int expectedOutboxCount = outcome == ProviderOutcome.SUCCESS ? 1 : 0;
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM outbox_events "
                        + "WHERE event_type = 'payment.succeeded.v1'",
                Integer.class
        )).isEqualTo(expectedOutboxCount);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ledger_transactions",
                Integer.class
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ledger_entries",
                Integer.class
        )).isZero();
        assertThat(paymentProvider.transactionActive()).isFalse();
        assertThat(paymentProvider.observedIdempotencyStatus())
                .isEqualTo(IdempotencyStatus.PROCESSING.name());
        assertThat(paymentProvider.observedResourceType()).isEqualTo(PAYMENT_RESOURCE);
        assertThat(paymentProvider.observedResourcePublicId()).isEqualTo(fixture.paymentId());
        assertThat(paymentProvider.observedPaymentStatus())
                .isEqualTo(PaymentStatus.PROCESSING.name());
        assertThat(paymentProvider.observedTransactionStatus())
                .isEqualTo(PaymentTransactionStatus.PROCESSING.name());

        IdempotencyRecord reservation = findConfirmReservation(
                fixture.merchantId(),
                "confirm-snapshot-" + suffix
        );
        assertThat(reservation.isCompleted()).isTrue();
        assertThat(reservation.resourceType()).isEqualTo(PAYMENT_RESOURCE);
        assertThat(reservation.resourcePublicId()).isEqualTo(fixture.paymentId());
        assertThat(reservation.httpStatus()).isEqualTo(result.httpStatus());
        assertThat(snapshotCodec.decode(reservation.responsePayload()))
                .isEqualTo(result.response());
        assertThat(reservation.responsePayload())
                .contains("\"paymentId\"", fixture.paymentId())
                .contains("\"transactionId\"", "ptxn_")
                .doesNotContain("internalId", "merchantId", "raw", "payload");
        assertThat(reservation.completedAt()).isNotNull();

        IdempotentConfirmPaymentResult replay = service.confirm(command(
                fixture,
                "confirm-snapshot-" + suffix
        ));

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.response()).isEqualTo(result.response());
        assertThat(replay.httpStatus()).isEqualTo(result.httpStatus());
        assertThat(paymentProvider.invocationCount()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM outbox_events "
                        + "WHERE event_type = 'payment.succeeded.v1'",
                Integer.class
        )).isEqualTo(expectedOutboxCount);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ledger_transactions",
                Integer.class
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ledger_entries",
                Integer.class
        )).isZero();
    }

    @Test
    void invalidOwnershipAndStateShouldCreateNoReservation() {
        PaymentFixture fixture = createPayment("preflight_invalid");
        long otherMerchantId = insertActiveMerchant("mrc_preflight_other");

        assertThatThrownBy(() -> service.confirm(new IdempotentConfirmPaymentCommand(
                principal("mrc_preflight_other"),
                IdempotencyKey.of("confirm-cross-merchant"),
                fixture.paymentId()
        ))).isInstanceOfSatisfying(ApiException.class, exception ->
                assertThat(exception.code()).isEqualTo(ErrorCode.PAYMENT_NOT_FOUND));

        PaymentIntent payment = paymentIntentRepository
                .findByPublicId(fixture.paymentId())
                .orElseThrow();
        payment.startProcessing(CREATED_AT.plusSeconds(1));
        paymentIntentRepository.save(payment);

        assertThatThrownBy(() -> service.confirm(command(
                fixture,
                "confirm-invalid-state"
        ))).isInstanceOfSatisfying(ApiException.class, exception ->
                assertThat(exception.code()).isEqualTo(ErrorCode.PAYMENT_INVALID_STATE));

        assertThat(countIdempotencyRows()).isZero();
        assertThat(otherMerchantId).isPositive();
        assertThat(paymentProvider.invocationCount()).isZero();
    }

    @Test
    void duplicateDecisionsShouldNeverReachProvider() {
        PaymentFixture processing = createPayment("duplicate_processing");
        reserve(processing, "confirm-processing");

        assertThatThrownBy(() -> service.confirm(command(
                processing,
                "confirm-processing"
        ))).isInstanceOf(IdempotencyRequestInProgressException.class);

        long reusedMerchantId = insertActiveMerchant("mrc_duplicate_reused");
        PaymentFixture first = createPaymentForMerchant(
                reusedMerchantId,
                "mrc_duplicate_reused",
                "pi_duplicate_reused_first"
        );
        PaymentFixture second = createPaymentForMerchant(
                reusedMerchantId,
                "mrc_duplicate_reused",
                "pi_duplicate_reused_second"
        );
        reserve(first, "confirm-reused");

        assertThatThrownBy(() -> service.confirm(command(second, "confirm-reused")))
                .isInstanceOf(IdempotencyKeyReusedException.class);

        PaymentFixture replay = createPayment("duplicate_replay");
        IdempotentConfirmPaymentResult original = service.confirm(command(
                replay,
                "confirm-replay"
        ));
        int outboxCountBeforeReplay = countRows("outbox_events");
        jdbcTemplate.update(
                "UPDATE payment_intents SET status = 'FAILED' WHERE public_id = ?",
                replay.paymentId()
        );

        IdempotentConfirmPaymentResult replayResult = service.confirm(command(
                replay,
                "confirm-replay"
        ));

        assertThat(replayResult.replayed()).isTrue();
        assertThat(replayResult.response()).isEqualTo(original.response());
        assertThat(replayResult.httpStatus()).isEqualTo(original.httpStatus());
        assertThat(replayResult.response().paymentStatus())
                .isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(paymentProvider.invocationCount()).isEqualTo(1);
        assertThat(countRows("outbox_events")).isEqualTo(outboxCountBeforeReplay);
        assertThat(countRows("ledger_transactions")).isZero();
        assertThat(countRows("ledger_entries")).isZero();
    }

    @Test
    void tx1FailureBeforeProviderShouldReleaseOwnedReservation() {
        PaymentFixture fixture = createPayment("release_tx1_failure");
        installTx1FailureTrigger(fixture.paymentId());

        assertThatThrownBy(() -> service.confirm(command(
                fixture,
                "confirm-release-tx1"
        ))).isInstanceOf(RuntimeException.class);

        assertThat(countConfirmReservations(
                fixture.merchantId(),
                "confirm-release-tx1"
        )).isZero();
        assertThat(paymentIntentRepository.findByPublicId(fixture.paymentId()).orElseThrow().status())
                .isEqualTo(PaymentStatus.CREATED);
        assertThat(paymentTransactionRepository.findByPaymentIntentId(fixture.paymentInternalId()))
                .isEmpty();
        assertThat(paymentProvider.invocationCount()).isZero();
    }

    @Test
    void failureAfterProviderStartsShouldRetainProcessingReservation() {
        PaymentFixture fixture = createPayment("retain_uncertain");
        paymentProvider.failUncertainly();

        assertThatThrownBy(() -> service.confirm(command(
                fixture,
                "confirm-retain-uncertain"
        ))).isInstanceOf(RuntimeException.class)
                .hasMessage("simulated uncertain provider failure");

        IdempotencyRecord reservation = findConfirmReservation(
                fixture.merchantId(),
                "confirm-retain-uncertain"
        );
        assertThat(reservation.isProcessing()).isTrue();
        assertThat(paymentIntentRepository.findByPublicId(fixture.paymentId()).orElseThrow().status())
                .isEqualTo(PaymentStatus.PROCESSING);
        assertThat(paymentTransactionRepository.findByPaymentIntentId(fixture.paymentInternalId()))
                .singleElement()
                .extracting(transaction -> transaction.status())
                .isEqualTo(PaymentTransactionStatus.PROCESSING);
        assertThat(paymentProvider.invocationCount()).isEqualTo(1);
    }

    @Test
    void concurrentSameConfirmShouldHaveOneExecutionOwner() throws Exception {
        PaymentFixture fixture = createPayment("concurrent_owner");
        IdempotentConfirmPaymentCommand command = command(
                fixture,
                "confirm-concurrent-owner"
        );
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Object> first = executor.submit(() -> captureResult(command));
            Future<Object> second = executor.submit(() -> captureResult(command));
            List<Object> results = List.of(
                    first.get(30, TimeUnit.SECONDS),
                    second.get(30, TimeUnit.SECONDS)
            );

            assertThat(results).filteredOn(IdempotentConfirmPaymentResult.class::isInstance)
                    .hasSizeBetween(1, 2);
            assertThat(results).allMatch(result ->
                    result instanceof IdempotentConfirmPaymentResult
                            || result instanceof IdempotencyRequestInProgressException
            );
            assertThat(results.stream()
                    .filter(IdempotentConfirmPaymentResult.class::isInstance)
                    .map(IdempotentConfirmPaymentResult.class::cast)
                    .filter(result -> !result.replayed()))
                    .hasSize(1);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(paymentProvider.invocationCount()).isEqualTo(1);
        assertThat(countConfirmReservations(
                fixture.merchantId(),
                "confirm-concurrent-owner"
        )).isEqualTo(1);
        assertThat(paymentTransactionRepository.findByPaymentIntentId(fixture.paymentInternalId()))
                .hasSize(1);
    }

    @Test
    void idempotencyScopeShouldRemainIndependentByMerchantAndOperation() {
        PaymentFixture first = createPayment("scope_first");
        PaymentFixture second = createPayment("scope_second");
        String sharedKey = "shared-confirm-key";

        acquisitionService.acquire(new IdempotencyAcquisitionCommand(
                first.merchantId(),
                IdempotencyOperation.PAYMENT_INTENT_CREATE,
                IdempotencyKey.of(sharedKey),
                "c".repeat(64)
        ));

        IdempotentConfirmPaymentResult firstResult = service.confirm(command(first, sharedKey));
        IdempotentConfirmPaymentResult secondResult = service.confirm(command(second, sharedKey));

        assertThat(firstResult.replayed()).isFalse();
        assertThat(secondResult.replayed()).isFalse();
        assertThat(paymentProvider.invocationCount()).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM idempotency_records WHERE idempotency_key = ?",
                Integer.class,
                sharedKey
        )).isEqualTo(3);
        assertThat(countConfirmReservations(first.merchantId(), sharedKey)).isEqualTo(1);
        assertThat(countConfirmReservations(second.merchantId(), sharedKey)).isEqualTo(1);
    }

    private Object captureResult(IdempotentConfirmPaymentCommand command) {
        try {
            return service.confirm(command);
        } catch (RuntimeException exception) {
            return exception;
        }
    }

    private IdempotencyAcquisitionResult reserve(PaymentFixture fixture, String key) {
        String requestHash = fingerprintService.fingerprint(
                ConfirmPaymentFingerprint.version1(fixture.paymentId())
        );
        return acquisitionService.acquire(IdempotencyAcquisitionCommand.forResource(
                fixture.merchantId(),
                IdempotencyOperation.PAYMENT_INTENT_CONFIRM,
                IdempotencyKey.of(key),
                requestHash,
                PAYMENT_RESOURCE,
                fixture.paymentId()
        ));
    }

    private IdempotencyRecord findConfirmReservation(long merchantId, String key) {
        return idempotencyRepository.findByScope(
                merchantId,
                IdempotencyOperation.PAYMENT_INTENT_CONFIRM,
                IdempotencyKey.of(key)
        ).orElseThrow();
    }

    private int countConfirmReservations(long merchantId, String key) {
        return jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM idempotency_records
                WHERE merchant_id = ?
                  AND operation = 'PAYMENT_INTENT_CONFIRM'
                  AND idempotency_key = ?
                """, Integer.class, merchantId, key);
    }

    private int countIdempotencyRows() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM idempotency_records",
                Integer.class
        );
    }

    private int countRows(String table) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private static PaymentStatus paymentStatus(ProviderOutcome outcome) {
        return switch (outcome) {
            case SUCCESS -> PaymentStatus.SUCCEEDED;
            case DECLINED, TECHNICAL_FAILURE -> PaymentStatus.FAILED;
            case UNKNOWN -> PaymentStatus.PROCESSING;
        };
    }

    private static PaymentTransactionStatus transactionStatus(ProviderOutcome outcome) {
        return switch (outcome) {
            case SUCCESS -> PaymentTransactionStatus.SUCCEEDED;
            case DECLINED, TECHNICAL_FAILURE -> PaymentTransactionStatus.FAILED;
            case UNKNOWN -> PaymentTransactionStatus.UNKNOWN;
        };
    }

    private PaymentFixture createPayment(String suffix) {
        String merchantPublicId = "mrc_" + suffix;
        long merchantId = insertActiveMerchant(merchantPublicId);
        return createPaymentForMerchant(
                merchantId,
                merchantPublicId,
                "pi_" + suffix
        );
    }

    private PaymentFixture createPaymentForMerchant(
            long merchantId,
            String merchantPublicId,
            String paymentPublicId
    ) {
        PaymentIntent payment = paymentIntentRepository.save(PaymentIntent.create(
                paymentPublicId,
                merchantId,
                "ORDER-" + paymentPublicId,
                "Confirm " + paymentPublicId,
                Money.of(50_000L, "VND"),
                CREATED_AT
        ));
        return new PaymentFixture(
                merchantId,
                merchantPublicId,
                payment.internalId(),
                payment.publicId()
        );
    }

    private long insertActiveMerchant(String publicId) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO merchants (public_id, name, status, created_at, updated_at, version)
                VALUES (?, ?, 'ACTIVE', ?, ?, 0)
                RETURNING id
                """, Long.class,
                publicId,
                "Confirm Idempotency Store",
                CREATED_AT.atOffset(ZoneOffset.UTC),
                CREATED_AT.atOffset(ZoneOffset.UTC)
        );
    }

    private IdempotentConfirmPaymentCommand command(PaymentFixture fixture, String key) {
        return new IdempotentConfirmPaymentCommand(
                principal(fixture.merchantPublicId()),
                IdempotencyKey.of(key),
                fixture.paymentId()
        );
    }

    private MerchantApiPrincipal principal(String merchantPublicId) {
        return new MerchantApiPrincipal(
                merchantPublicId,
                "key_" + merchantPublicId
        );
    }

    private void installTx1FailureTrigger(String paymentPublicId) {
        jdbcTemplate.execute("""
                CREATE OR REPLACE FUNCTION fail_confirm_tx1() RETURNS trigger AS $$
                BEGIN
                    IF NEW.public_id = '%s'
                       AND NEW.status = 'PROCESSING' THEN
                        RAISE EXCEPTION 'simulated TX1 failure';
                    END IF;
                    RETURN NEW;
                END;
                $$ LANGUAGE plpgsql
                """.formatted(paymentPublicId));
        jdbcTemplate.execute("""
                CREATE TRIGGER fail_confirm_tx1_trigger
                BEFORE UPDATE ON payment_intents
                FOR EACH ROW EXECUTE FUNCTION fail_confirm_tx1()
                """);
    }

    private void dropTx1FailureTrigger() {
        jdbcTemplate.execute("""
                DROP TRIGGER IF EXISTS fail_confirm_tx1_trigger ON payment_intents
                """);
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS fail_confirm_tx1()");
    }

    private record PaymentFixture(
            long merchantId,
            String merchantPublicId,
            long paymentInternalId,
            String paymentId
    ) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProviderTestConfiguration {

        @Bean
        @Primary
        InspectingConfirmProvider inspectingConfirmProvider(JdbcTemplate jdbcTemplate) {
            return new InspectingConfirmProvider(jdbcTemplate);
        }
    }

    static final class InspectingConfirmProvider implements PaymentProviderPort {

        private final JdbcTemplate jdbcTemplate;
        private final AtomicInteger invocationCount = new AtomicInteger();
        private volatile boolean failUncertainly;
        private volatile ProviderOutcome outcome = ProviderOutcome.SUCCESS;
        private volatile boolean transactionActive;
        private volatile String observedIdempotencyStatus;
        private volatile String observedResourceType;
        private volatile String observedResourcePublicId;
        private volatile String observedPaymentStatus;
        private volatile String observedTransactionStatus;

        private InspectingConfirmProvider(JdbcTemplate jdbcTemplate) {
            this.jdbcTemplate = jdbcTemplate;
        }

        @Override
        public PaymentProviderResult charge(PaymentProviderRequest request) {
            invocationCount.incrementAndGet();
            transactionActive = TransactionSynchronizationManager.isActualTransactionActive();
            observedIdempotencyStatus = jdbcTemplate.queryForObject("""
                    SELECT status
                    FROM idempotency_records
                    WHERE operation = 'PAYMENT_INTENT_CONFIRM'
                      AND resource_public_id = ?
                    """, String.class, request.paymentPublicReference());
            observedResourceType = jdbcTemplate.queryForObject("""
                    SELECT resource_type
                    FROM idempotency_records
                    WHERE operation = 'PAYMENT_INTENT_CONFIRM'
                      AND resource_public_id = ?
                    """, String.class, request.paymentPublicReference());
            observedResourcePublicId = jdbcTemplate.queryForObject("""
                    SELECT resource_public_id
                    FROM idempotency_records
                    WHERE operation = 'PAYMENT_INTENT_CONFIRM'
                      AND resource_public_id = ?
                    """, String.class, request.paymentPublicReference());
            observedPaymentStatus = jdbcTemplate.queryForObject(
                    "SELECT status FROM payment_intents WHERE public_id = ?",
                    String.class,
                    request.paymentPublicReference()
            );
            observedTransactionStatus = jdbcTemplate.queryForObject("""
                    SELECT pt.status
                    FROM payment_transactions pt
                    JOIN payment_intents pi ON pi.id = pt.payment_intent_id
                    WHERE pi.public_id = ?
                    """, String.class, request.paymentPublicReference());
            if (failUncertainly) {
                throw new RuntimeException("simulated uncertain provider failure");
            }
            return resultFor(outcome, request.paymentPublicReference());
        }

        void failUncertainly() {
            failUncertainly = true;
        }

        void respondWith(ProviderOutcome outcome) {
            this.outcome = outcome;
        }

        PaymentProviderResult resultFor(ProviderOutcome outcome) {
            return resultFor(outcome, "pi_snapshot");
        }

        private PaymentProviderResult resultFor(
                ProviderOutcome outcome,
                String paymentPublicId
        ) {
            return switch (outcome) {
                case SUCCESS -> new PaymentProviderResult(
                        "SIMULATOR",
                        outcome,
                        "sim_" + paymentPublicId,
                        null,
                        null
                );
                case DECLINED -> new PaymentProviderResult(
                        "SIMULATOR",
                        outcome,
                        "sim_" + paymentPublicId,
                        "CARD_DECLINED",
                        "The provider declined the payment."
                );
                case UNKNOWN -> new PaymentProviderResult(
                        "SIMULATOR",
                        outcome,
                        null,
                        "PROVIDER_TIMEOUT",
                        "The provider outcome is unknown."
                );
                case TECHNICAL_FAILURE -> new PaymentProviderResult(
                        "SIMULATOR",
                        outcome,
                        null,
                        "PROVIDER_UNAVAILABLE",
                        "The provider operation did not complete."
                );
            };
        }

        void reset() {
            invocationCount.set(0);
            failUncertainly = false;
            outcome = ProviderOutcome.SUCCESS;
            transactionActive = false;
            observedIdempotencyStatus = null;
            observedResourceType = null;
            observedResourcePublicId = null;
            observedPaymentStatus = null;
            observedTransactionStatus = null;
        }

        int invocationCount() {
            return invocationCount.get();
        }

        boolean transactionActive() {
            return transactionActive;
        }

        String observedIdempotencyStatus() {
            return observedIdempotencyStatus;
        }

        String observedResourceType() {
            return observedResourceType;
        }

        String observedResourcePublicId() {
            return observedResourcePublicId;
        }

        String observedPaymentStatus() {
            return observedPaymentStatus;
        }

        String observedTransactionStatus() {
            return observedTransactionStatus;
        }
    }
}
