package com.flowpay.backend.refund.application;

import com.flowpay.backend.common.error.ApiException;
import com.flowpay.backend.common.error.ErrorCode;
import com.flowpay.backend.common.security.MerchantApiPrincipal;
import com.flowpay.backend.idempotency.application.IdempotencyKeyReusedException;
import com.flowpay.backend.idempotency.application.IdempotencyRequestInProgressException;
import com.flowpay.backend.idempotency.domain.IdempotencyKey;
import com.flowpay.backend.refund.domain.RefundProviderOutcome;
import com.flowpay.backend.refund.domain.RefundReason;
import com.flowpay.backend.refund.domain.RefundStatus;
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
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(IdempotentRefundIntegrationTest.ProviderTestConfiguration.class)
class IdempotentRefundIntegrationTest extends PostgresIntegrationTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-31T08:00:00Z");

    @Autowired
    private IdempotentRefundService service;

    @Autowired
    private PrepareRefundService preparationService;

    @Autowired
    private RefundResponseSnapshotCodec snapshotCodec;

    @Autowired
    private InspectingRefundProvider refundProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanData() {
        dropCompletionFailureTrigger();
        jdbcTemplate.update("""
                TRUNCATE TABLE outbox_events, ledger_entries, ledger_transactions,
                    ledger_accounts, refunds, idempotency_records, payment_transactions,
                    payment_intents, merchant_members, merchant_api_keys, refresh_tokens,
                    merchants, users RESTART IDENTITY CASCADE
                """);
        refundProvider.reset();
    }

    @AfterEach
    void cleanTrigger() {
        dropCompletionFailureTrigger();
    }

    @ParameterizedTest
    @EnumSource(RefundProviderOutcome.class)
    void shouldPersistReplayableSnapshotForEveryNormalizedOutcome(
            RefundProviderOutcome outcome
    ) {
        String suffix = outcome.name().toLowerCase();
        PaymentFixture fixture = insertRefundablePayment("snapshot_" + suffix);
        IdempotentRefundCommand command = command(
                fixture,
                "refund-snapshot-" + suffix,
                400L
        );
        refundProvider.respondWith(outcome);

        IdempotentRefundResult result = service.create(command);

        assertThat(result.replayed()).isFalse();
        assertThat(result.response().id()).startsWith("re_");
        assertThat(result.response().paymentId()).isEqualTo(fixture.paymentPublicId());
        assertThat(result.response().amount()).isEqualTo(400L);
        assertThat(result.response().currency()).isEqualTo("USD");
        assertThat(result.response().status()).isEqualTo(refundStatus(outcome));
        assertThat(result.response().reason()).isEqualTo("Customer request");
        assertThat(result.response().provider()).isEqualTo("SIMULATOR");
        assertThat(result.response().failureCode())
                .isEqualTo(refundProvider.resultFor(outcome, result.response().id()).failureCode());
        assertThat(result.httpStatus()).isEqualTo(
                outcome == RefundProviderOutcome.UNKNOWN ? 202 : 201
        );
        assertThat(refundProvider.invocationCount()).isEqualTo(1);
        assertThat(refundProvider.transactionActive()).isFalse();
        assertThat(refundProvider.observedRefundStatus()).isEqualTo("PROCESSING");
        assertThat(refundProvider.observedReservedAmount()).isEqualTo(400L);
        assertThat(refundProvider.observedRefundedAmount()).isZero();
        assertThat(refundProvider.observedIdempotencyStatus()).isEqualTo("PROCESSING");
        assertThat(refundProvider.paymentLockAvailable()).isTrue();
        assertFinalCapacity(fixture, outcome, 400L);

        Map<String, Object> idempotency = idempotency(
                fixture.merchantId(),
                "refund-snapshot-" + suffix
        );
        assertThat(idempotency.get("status")).isEqualTo("COMPLETED");
        assertThat(idempotency.get("resource_type")).isEqualTo("REFUND");
        assertThat(idempotency.get("resource_public_id"))
                .isEqualTo(result.response().id());
        assertThat(((Number) idempotency.get("http_status")).intValue())
                .isEqualTo(result.httpStatus());
        String payload = idempotency.get("response_payload").toString();
        assertThat(snapshotCodec.decode(payload)).isEqualTo(result.response());
        assertThat(payload)
                .contains("\"id\"", "\"paymentId\"", "\"amount\"", "\"currency\"")
                .contains("\"status\"", "\"reason\"", "\"provider\"")
                .contains("\"providerRefundId\"", "\"failureCode\"")
                .contains("\"failureMessage\"", "\"createdAt\"")
                .contains("\"updatedAt\"", "\"completedAt\"")
                .doesNotContain("internalId", "merchantId", "version", "requestHash");

        IdempotentRefundResult replay = service.create(command);

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.response()).isEqualTo(result.response());
        assertThat(replay.httpStatus()).isEqualTo(result.httpStatus());
        assertThat(refundProvider.invocationCount()).isEqualTo(1);
        assertThat(countRefunds(fixture.merchantId())).isEqualTo(1);
        assertThat(countRefundEvents()).isEqualTo(
                outcome == RefundProviderOutcome.SUCCESS ? 1 : 0
        );
        assertThat(countRows("ledger_transactions")).isZero();
        assertThat(countRows("ledger_entries")).isZero();
    }

    @Test
    void duplicateDecisionsAndPreparationFailureShouldNeverReachProvider() {
        PaymentFixture processing = insertRefundablePayment("in_progress");
        IdempotentRefundCommand processingCommand = command(
                processing,
                "refund-in-progress",
                200L
        );
        preparationService.prepare(processingCommand.toPrepareCommand());

        assertThatThrownBy(() -> service.create(processingCommand))
                .isInstanceOf(IdempotencyRequestInProgressException.class);

        PaymentFixture reused = insertRefundablePayment("key_reused");
        IdempotentRefundCommand original = command(reused, "refund-key-reused", 200L);
        preparationService.prepare(original.toPrepareCommand());

        assertThatThrownBy(() -> service.create(command(
                reused,
                "refund-key-reused",
                201L
        ))).isInstanceOf(IdempotencyKeyReusedException.class);

        PaymentFixture rejected = insertRefundablePayment("rejected");
        assertThatThrownBy(() -> service.create(command(
                rejected,
                "refund-rejected",
                1_001L
        ))).isInstanceOfSatisfying(ApiException.class, exception ->
                assertThat(exception.code()).isEqualTo(
                        ErrorCode.REFUND_AMOUNT_EXCEEDS_AVAILABLE
                )
        );

        assertThat(refundProvider.invocationCount()).isZero();
        assertThat(countIdempotency(rejected.merchantId(), "refund-rejected")).isZero();
        assertCapacity(rejected, 0L, 0L, "SUCCEEDED");
    }

    @Test
    void unexpectedProviderExceptionShouldPreserveSafeProcessingState() {
        PaymentFixture fixture = insertRefundablePayment("uncertain");
        refundProvider.failUncertainly();

        assertThatThrownBy(() -> service.create(command(
                fixture,
                "refund-uncertain",
                300L
        ))).isInstanceOf(RuntimeException.class)
                .hasMessage("simulated uncertain Refund provider failure");

        assertThat(refundProvider.invocationCount()).isEqualTo(1);
        assertThat(singleRefundStatus(fixture.merchantId())).isEqualTo("PROCESSING");
        assertCapacity(fixture, 0L, 300L, "SUCCEEDED");
        assertThat(countRefundEvents()).isZero();
        Map<String, Object> idempotency = idempotency(
                fixture.merchantId(),
                "refund-uncertain"
        );
        assertThat(idempotency.get("status")).isEqualTo("PROCESSING");
        assertThat(idempotency.get("http_status")).isNull();
        assertThat(idempotency.get("response_payload")).isNull();
    }

    @Test
    void completionFailureShouldNotCauseProviderReexecution() {
        PaymentFixture fixture = insertRefundablePayment("completion_failure");
        IdempotentRefundCommand command = command(
                fixture,
                "refund-completion-failure",
                250L
        );
        installCompletionFailureTrigger();

        assertThatThrownBy(() -> service.create(command))
                .isInstanceOf(DataAccessException.class);

        assertThat(refundProvider.invocationCount()).isEqualTo(1);
        assertThat(singleRefundStatus(fixture.merchantId())).isEqualTo("SUCCEEDED");
        assertCapacity(fixture, 250L, 0L, "PARTIALLY_REFUNDED");
        assertThat(idempotency(fixture.merchantId(), "refund-completion-failure")
                .get("status")).isEqualTo("PROCESSING");
        assertThat(countRefundEvents()).isEqualTo(1);

        assertThatThrownBy(() -> service.create(command))
                .isInstanceOf(IdempotencyRequestInProgressException.class);
        assertThat(refundProvider.invocationCount()).isEqualTo(1);
    }

    private PaymentFixture insertRefundablePayment(String suffix) {
        String merchantPublicId = "mrc_orchestrate_" + suffix;
        long merchantId = insertMerchant(merchantPublicId);
        String paymentPublicId = "pi_orchestrate_" + suffix;
        Long paymentInternalId = jdbcTemplate.queryForObject(
                """
                INSERT INTO payment_intents (
                    public_id, merchant_id, amount_minor, currency, status,
                    created_at, updated_at, version
                )
                VALUES (?, ?, 1000, 'USD', 'SUCCEEDED', ?, ?, 0)
                RETURNING id
                """,
                Long.class,
                paymentPublicId,
                merchantId,
                utc(CREATED_AT),
                utc(CREATED_AT.plusSeconds(2))
        );
        jdbcTemplate.update(
                """
                INSERT INTO payment_transactions (
                    public_id, payment_intent_id, attempt_no, provider,
                    provider_transaction_id, status, started_at, completed_at,
                    created_at, updated_at, version
                )
                VALUES (?, ?, 1, 'SIMULATOR', ?, 'SUCCEEDED', ?, ?, ?, ?, 0)
                """,
                "ptxn_orchestrate_" + suffix,
                paymentInternalId,
                "provider_charge_" + suffix,
                utc(CREATED_AT.plusSeconds(3)),
                utc(CREATED_AT.plusSeconds(4)),
                utc(CREATED_AT.plusSeconds(3)),
                utc(CREATED_AT.plusSeconds(4))
        );
        return new PaymentFixture(
                merchantId,
                merchantPublicId,
                paymentInternalId,
                paymentPublicId
        );
    }

    private long insertMerchant(String publicId) {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO merchants (public_id, name, status, created_at, updated_at, version)
                VALUES (?, 'Refund Orchestration Store', 'ACTIVE', ?, ?, 0)
                RETURNING id
                """,
                Long.class,
                publicId,
                utc(CREATED_AT),
                utc(CREATED_AT)
        );
    }

    private static IdempotentRefundCommand command(
            PaymentFixture fixture,
            String key,
            long amountMinor
    ) {
        return new IdempotentRefundCommand(
                new MerchantApiPrincipal(
                        fixture.merchantPublicId(),
                        "key_" + fixture.merchantPublicId()
                ),
                IdempotencyKey.of(key),
                fixture.paymentPublicId(),
                amountMinor,
                RefundReason.of(" Customer request ")
        );
    }

    private void assertFinalCapacity(
            PaymentFixture fixture,
            RefundProviderOutcome outcome,
            long amount
    ) {
        switch (outcome) {
            case SUCCESS -> assertCapacity(
                    fixture,
                    amount,
                    0L,
                    "PARTIALLY_REFUNDED"
            );
            case DECLINED, TECHNICAL_FAILURE -> assertCapacity(
                    fixture,
                    0L,
                    0L,
                    "SUCCEEDED"
            );
            case UNKNOWN -> assertCapacity(
                    fixture,
                    0L,
                    amount,
                    "SUCCEEDED"
            );
        }
    }

    private void assertCapacity(
            PaymentFixture fixture,
            long expectedRefunded,
            long expectedReserved,
            String expectedStatus
    ) {
        Map<String, Object> payment = jdbcTemplate.queryForMap(
                """
                SELECT status, amount_minor, refunded_amount_minor, refund_reserved_minor
                FROM payment_intents
                WHERE id = ?
                """,
                fixture.paymentInternalId()
        );
        long amount = ((Number) payment.get("amount_minor")).longValue();
        long refunded = ((Number) payment.get("refunded_amount_minor")).longValue();
        long reserved = ((Number) payment.get("refund_reserved_minor")).longValue();
        assertThat(payment.get("status")).isEqualTo(expectedStatus);
        assertThat(refunded).isEqualTo(expectedRefunded).isNotNegative();
        assertThat(reserved).isEqualTo(expectedReserved).isNotNegative();
        assertThat(refunded + reserved).isLessThanOrEqualTo(amount);
    }

    private Map<String, Object> idempotency(long merchantId, String key) {
        return jdbcTemplate.queryForMap(
                """
                SELECT status, resource_type, resource_public_id, http_status,
                    response_payload
                FROM idempotency_records
                WHERE merchant_id = ?
                  AND operation = 'REFUND_CREATE'
                  AND idempotency_key = ?
                """,
                merchantId,
                key
        );
    }

    private int countIdempotency(long merchantId, String key) {
        return jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM idempotency_records
                WHERE merchant_id = ?
                  AND operation = 'REFUND_CREATE'
                  AND idempotency_key = ?
                """,
                Integer.class,
                merchantId,
                key
        );
    }

    private int countRefunds(long merchantId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM refunds WHERE merchant_id = ?",
                Integer.class,
                merchantId
        );
    }

    private int countRefundEvents() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM outbox_events "
                        + "WHERE event_type = 'refund.succeeded.v1'",
                Integer.class
        );
    }

    private int countRows(String table) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private String singleRefundStatus(long merchantId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM refunds WHERE merchant_id = ?",
                String.class,
                merchantId
        );
    }

    private void installCompletionFailureTrigger() {
        jdbcTemplate.execute("""
                CREATE OR REPLACE FUNCTION fail_refund_idempotency_completion()
                RETURNS trigger AS $$
                BEGIN
                    IF NEW.operation = 'REFUND_CREATE' AND NEW.status = 'COMPLETED' THEN
                        RAISE EXCEPTION 'forced Refund Idempotency completion failure';
                    END IF;
                    RETURN NEW;
                END;
                $$ LANGUAGE plpgsql
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER fail_refund_idempotency_completion_trigger
                BEFORE UPDATE ON idempotency_records
                FOR EACH ROW EXECUTE FUNCTION fail_refund_idempotency_completion()
                """);
    }

    private void dropCompletionFailureTrigger() {
        jdbcTemplate.execute("""
                DROP TRIGGER IF EXISTS fail_refund_idempotency_completion_trigger
                ON idempotency_records
                """);
        jdbcTemplate.execute(
                "DROP FUNCTION IF EXISTS fail_refund_idempotency_completion()"
        );
    }

    private static RefundStatus refundStatus(RefundProviderOutcome outcome) {
        return switch (outcome) {
            case SUCCESS -> RefundStatus.SUCCEEDED;
            case DECLINED, TECHNICAL_FAILURE -> RefundStatus.FAILED;
            case UNKNOWN -> RefundStatus.PROCESSING;
        };
    }

    private static java.time.OffsetDateTime utc(Instant value) {
        return value.atOffset(ZoneOffset.UTC);
    }

    private record PaymentFixture(
            long merchantId,
            String merchantPublicId,
            long paymentInternalId,
            String paymentPublicId
    ) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProviderTestConfiguration {

        @Bean
        @Primary
        InspectingRefundProvider inspectingRefundProvider(JdbcTemplate jdbcTemplate) {
            return new InspectingRefundProvider(jdbcTemplate);
        }
    }

    static final class InspectingRefundProvider implements RefundProviderPort {

        private final JdbcTemplate jdbcTemplate;
        private final AtomicInteger invocationCount = new AtomicInteger();
        private volatile RefundProviderOutcome outcome = RefundProviderOutcome.SUCCESS;
        private volatile boolean failUncertainly;
        private volatile boolean transactionActive;
        private volatile String observedRefundStatus;
        private volatile long observedReservedAmount;
        private volatile long observedRefundedAmount;
        private volatile String observedIdempotencyStatus;
        private volatile boolean paymentLockAvailable;

        private InspectingRefundProvider(JdbcTemplate jdbcTemplate) {
            this.jdbcTemplate = jdbcTemplate;
        }

        @Override
        public RefundProviderResult refund(RefundProviderRequest request) {
            invocationCount.incrementAndGet();
            transactionActive = TransactionSynchronizationManager
                    .isActualTransactionActive();
            observedRefundStatus = jdbcTemplate.queryForObject(
                    "SELECT status FROM refunds WHERE public_id = ?",
                    String.class,
                    request.refundPublicId()
            );
            Map<String, Object> payment = jdbcTemplate.queryForMap(
                    """
                    SELECT refunded_amount_minor, refund_reserved_minor
                    FROM payment_intents
                    WHERE public_id = ?
                    """,
                    request.paymentPublicId()
            );
            observedRefundedAmount = ((Number) payment.get("refunded_amount_minor"))
                    .longValue();
            observedReservedAmount = ((Number) payment.get("refund_reserved_minor"))
                    .longValue();
            observedIdempotencyStatus = jdbcTemplate.queryForObject(
                    """
                    SELECT status
                    FROM idempotency_records
                    WHERE operation = 'REFUND_CREATE'
                    ORDER BY id DESC
                    LIMIT 1
                    """,
                    String.class
            );
            paymentLockAvailable = jdbcTemplate.queryForObject(
                    """
                    SELECT true
                    FROM payment_intents
                    WHERE public_id = ?
                    FOR UPDATE NOWAIT
                    """,
                    Boolean.class,
                    request.paymentPublicId()
            );
            if (failUncertainly) {
                throw new RuntimeException("simulated uncertain Refund provider failure");
            }
            return resultFor(outcome, request.refundPublicId());
        }

        RefundProviderResult resultFor(
                RefundProviderOutcome outcome,
                String refundPublicId
        ) {
            return switch (outcome) {
                case SUCCESS -> new RefundProviderResult(
                        "SIMULATOR",
                        outcome,
                        "provider_" + refundPublicId,
                        null,
                        null
                );
                case DECLINED -> new RefundProviderResult(
                        "SIMULATOR",
                        outcome,
                        null,
                        "REFUND_DECLINED",
                        "The provider declined the refund."
                );
                case UNKNOWN -> new RefundProviderResult(
                        "SIMULATOR",
                        outcome,
                        null,
                        "PROVIDER_TIMEOUT",
                        "The Refund provider outcome is unknown."
                );
                case TECHNICAL_FAILURE -> new RefundProviderResult(
                        "SIMULATOR",
                        outcome,
                        null,
                        "PROVIDER_UNAVAILABLE",
                        "The Refund provider operation did not complete."
                );
            };
        }

        void respondWith(RefundProviderOutcome outcome) {
            this.outcome = outcome;
        }

        void failUncertainly() {
            failUncertainly = true;
        }

        void reset() {
            invocationCount.set(0);
            outcome = RefundProviderOutcome.SUCCESS;
            failUncertainly = false;
            transactionActive = false;
            observedRefundStatus = null;
            observedReservedAmount = 0L;
            observedRefundedAmount = 0L;
            observedIdempotencyStatus = null;
            paymentLockAvailable = false;
        }

        int invocationCount() {
            return invocationCount.get();
        }

        boolean transactionActive() {
            return transactionActive;
        }

        String observedRefundStatus() {
            return observedRefundStatus;
        }

        long observedReservedAmount() {
            return observedReservedAmount;
        }

        long observedRefundedAmount() {
            return observedRefundedAmount;
        }

        String observedIdempotencyStatus() {
            return observedIdempotencyStatus;
        }

        boolean paymentLockAvailable() {
            return paymentLockAvailable;
        }
    }
}
