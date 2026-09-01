package com.flowpay.backend.refund.application;

import com.flowpay.backend.common.error.ApiException;
import com.flowpay.backend.common.error.ErrorCode;
import com.flowpay.backend.common.money.Money;
import com.flowpay.backend.refund.domain.Refund;
import com.flowpay.backend.refund.domain.RefundProviderOutcome;
import com.flowpay.backend.refund.domain.RefundStatus;
import com.flowpay.backend.testing.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;

@SpringBootTest
@ActiveProfiles("test")
class FinalizeRefundIntegrationTest extends PostgresIntegrationTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-31T08:00:00Z");

    @MockitoBean
    private RefundProviderPort refundProvider;

    @Autowired
    private FinalizeRefundService service;

    @Autowired
    private RefundRepository refundRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanData() {
        dropFailureTriggers();
        jdbcTemplate.update("""
                TRUNCATE TABLE refunds, idempotency_records, payment_transactions,
                    payment_intents, merchant_members, merchant_api_keys, refresh_tokens,
                    merchants, users RESTART IDENTITY CASCADE
                """);
    }

    @AfterEach
    void verifyProviderBoundaryAndCleanTriggers() {
        dropFailureTriggers();
        verifyNoInteractions(refundProvider);
    }

    @Test
    void successShouldAtomicallyCompletePartialRefund() {
        RefundFixture fixture = insertProcessingRefund("partial", 1_000L, 400L);

        FinalizedRefund finalized = service.finalizeRefund(command(
                fixture,
                success("provider_refund_partial")
        ));

        assertThat(finalized.refundPublicId()).isEqualTo(fixture.refundPublicId());
        assertThat(finalized.paymentPublicId()).isEqualTo(fixture.paymentPublicId());
        assertThat(finalized.amount()).isEqualTo(Money.of(400L, "USD"));
        assertThat(finalized.status()).isEqualTo(RefundStatus.SUCCEEDED);
        assertThat(finalized.provider()).isEqualTo("SIMULATOR");
        assertThat(finalized.providerRefundId()).isEqualTo("provider_refund_partial");
        assertThat(finalized.failureCode()).isNull();
        assertThat(finalized.failureMessage()).isNull();
        assertThat(finalized.completedAt()).isNotNull();
        assertRefund(fixture, RefundStatus.SUCCEEDED, "provider_refund_partial", null);
        assertCapacity(fixture, 400L, 0L, "PARTIALLY_REFUNDED");
    }

    @Test
    void successForExactRemainingAmountShouldMarkPaymentRefunded() {
        RefundFixture fixture = insertProcessingRefund("exact", 1_000L, 1_000L);

        service.finalizeRefund(command(fixture, success("provider_refund_exact")));

        assertRefund(fixture, RefundStatus.SUCCEEDED, "provider_refund_exact", null);
        assertCapacity(fixture, 1_000L, 0L, "REFUNDED");
    }

    @ParameterizedTest
    @EnumSource(
            value = RefundProviderOutcome.class,
            names = {"DECLINED", "TECHNICAL_FAILURE"}
    )
    void knownFailureShouldReleaseReservationAndFailRefund(
            RefundProviderOutcome outcome
    ) {
        RefundFixture fixture = insertProcessingRefund(
                outcome.name().toLowerCase(),
                1_000L,
                350L
        );
        RefundProviderResult providerResult = failure(
                outcome,
                outcome.name(),
                "The provider operation did not complete."
        );

        FinalizedRefund finalized = service.finalizeRefund(command(
                fixture,
                providerResult
        ));

        assertThat(finalized.status()).isEqualTo(RefundStatus.FAILED);
        assertThat(finalized.failureCode()).isEqualTo(outcome.name());
        assertThat(finalized.failureMessage()).isEqualTo(
                "The provider operation did not complete."
        );
        assertRefund(fixture, RefundStatus.FAILED, null, outcome.name());
        assertCapacity(fixture, 0L, 0L, "SUCCEEDED");
    }

    @Test
    void unknownShouldRetainReservationAndKeepRefundProcessing() {
        RefundFixture fixture = insertProcessingRefund("unknown", 1_000L, 275L);
        RefundProviderResult unknown = new RefundProviderResult(
                "SIMULATOR",
                RefundProviderOutcome.UNKNOWN,
                "provider_refund_pending",
                "PROVIDER_TIMEOUT",
                "The provider outcome is unknown."
        );

        FinalizedRefund finalized = service.finalizeRefund(command(fixture, unknown));

        assertThat(finalized.status()).isEqualTo(RefundStatus.PROCESSING);
        assertThat(finalized.providerRefundId()).isEqualTo("provider_refund_pending");
        assertThat(finalized.failureCode()).isEqualTo("PROVIDER_TIMEOUT");
        assertThat(finalized.completedAt()).isNull();
        assertRefund(
                fixture,
                RefundStatus.PROCESSING,
                "provider_refund_pending",
                "PROVIDER_TIMEOUT"
        );
        assertCapacity(fixture, 0L, 275L, "SUCCEEDED");
    }

    @Test
    void terminalRefundShouldRejectSecondFinalizationBeforePaymentMutation() {
        RefundFixture fixture = insertProcessingRefund("terminal", 1_000L, 300L);
        FinalizeRefundCommand command = command(
                fixture,
                success("provider_refund_terminal")
        );
        service.finalizeRefund(command);

        assertThatThrownBy(() -> service.finalizeRefund(command))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Refund cannot be finalized from SUCCEEDED");

        assertRefund(fixture, RefundStatus.SUCCEEDED, "provider_refund_terminal", null);
        assertCapacity(fixture, 300L, 0L, "PARTIALLY_REFUNDED");
    }

    @Test
    void concurrentFinalizersShouldConsumeReservationExactlyOnce() throws Exception {
        RefundFixture fixture = insertProcessingRefund("concurrent", 1_000L, 450L);
        FinalizeRefundCommand command = command(
                fixture,
                success("provider_refund_concurrent")
        );
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Future<FinalizationAttempt>> attempts = List.of(
                    executor.submit(() -> finalizeAfter(start, command)),
                    executor.submit(() -> finalizeAfter(start, command))
            );
            start.countDown();

            List<FinalizationAttempt> results = attempts.stream()
                    .map(FinalizeRefundIntegrationTest::await)
                    .toList();
            assertThat(results).filteredOn(FinalizationAttempt::succeeded).hasSize(1);
            assertThat(results).filteredOn(result -> !result.succeeded()).singleElement()
                    .extracting(FinalizationAttempt::failure)
                    .isInstanceOf(IllegalStateException.class);
        }

        assertRefund(
                fixture,
                RefundStatus.SUCCEEDED,
                "provider_refund_concurrent",
                null
        );
        assertCapacity(fixture, 450L, 0L, "PARTIALLY_REFUNDED");
    }

    @Test
    void refundSaveFailureShouldRollbackCompletedPaymentCapacity() {
        RefundFixture fixture = insertProcessingRefund("refund_failure", 1_000L, 325L);
        createRefundUpdateFailureTrigger();

        assertThatThrownBy(() -> service.finalizeRefund(command(
                fixture,
                success("provider_refund_failure")
        ))).isInstanceOf(DataAccessException.class);

        assertRefund(fixture, RefundStatus.PROCESSING, null, null);
        assertCapacity(fixture, 0L, 325L, "SUCCEEDED");
    }

    @Test
    void paymentMutationFailureShouldLeaveRefundAndCapacityProcessing() {
        RefundFixture fixture = insertProcessingRefund("payment_failure", 1_000L, 325L);
        createPaymentUpdateFailureTrigger();

        assertThatThrownBy(() -> service.finalizeRefund(command(
                fixture,
                success("provider_payment_failure")
        ))).isInstanceOf(DataAccessException.class);

        assertRefund(fixture, RefundStatus.PROCESSING, null, null);
        assertCapacity(fixture, 0L, 325L, "SUCCEEDED");
    }

    @Test
    void crossMerchantRefundShouldUseNotFoundContractWithoutMutation() {
        RefundFixture fixture = insertProcessingRefund("ownership", 1_000L, 200L);
        long otherMerchantId = insertMerchant("mrc_finalize_other");
        FinalizeRefundCommand crossMerchant = new FinalizeRefundCommand(
                otherMerchantId,
                fixture.refundPublicId(),
                fixture.paymentPublicId(),
                success("provider_cross_merchant")
        );

        assertThatThrownBy(() -> service.finalizeRefund(crossMerchant))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.status().value()).isEqualTo(404);
                    assertThat(exception.code()).isEqualTo(ErrorCode.REFUND_NOT_FOUND);
                });

        assertRefund(fixture, RefundStatus.PROCESSING, null, null);
        assertCapacity(fixture, 0L, 200L, "SUCCEEDED");
    }

    @Test
    void mismatchedProviderShouldRejectBeforePaymentMutation() {
        RefundFixture fixture = insertProcessingRefund("provider_mismatch", 1_000L, 200L);
        RefundProviderResult mismatched = new RefundProviderResult(
                "OTHER_PROVIDER",
                RefundProviderOutcome.SUCCESS,
                "provider_mismatch",
                null,
                null
        );

        assertThatThrownBy(() -> service.finalizeRefund(command(fixture, mismatched)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Refund provider result does not match the prepared provider");

        assertRefund(fixture, RefundStatus.PROCESSING, null, null);
        assertCapacity(fixture, 0L, 200L, "SUCCEEDED");
    }

    private FinalizationAttempt finalizeAfter(
            CountDownLatch start,
            FinalizeRefundCommand command
    ) {
        await(start);
        try {
            service.finalizeRefund(command);
            return new FinalizationAttempt(true, null);
        } catch (RuntimeException exception) {
            return new FinalizationAttempt(false, exception);
        }
    }

    private RefundFixture insertProcessingRefund(
            String suffix,
            long paymentAmount,
            long refundAmount
    ) {
        String merchantPublicId = "mrc_finalize_" + suffix;
        long merchantId = insertMerchant(merchantPublicId);
        String paymentPublicId = "pi_finalize_" + suffix;
        Long paymentInternalId = jdbcTemplate.queryForObject(
                """
                INSERT INTO payment_intents (
                    public_id, merchant_id, amount_minor, currency, status,
                    refunded_amount_minor, refund_reserved_minor,
                    created_at, updated_at, version
                )
                VALUES (?, ?, ?, 'USD', 'SUCCEEDED', 0, ?, ?, ?, 0)
                RETURNING id
                """,
                Long.class,
                paymentPublicId,
                merchantId,
                paymentAmount,
                refundAmount,
                utc(CREATED_AT),
                utc(CREATED_AT.plusSeconds(2))
        );
        String refundPublicId = "re_finalize_" + suffix;
        jdbcTemplate.update(
                """
                INSERT INTO refunds (
                    public_id, merchant_id, payment_intent_id, amount_minor, currency,
                    status, reason, provider, created_at, updated_at, version
                )
                VALUES (?, ?, ?, ?, 'USD', 'PROCESSING', 'CUSTOMER_REQUEST',
                    'SIMULATOR', ?, ?, 0)
                """,
                refundPublicId,
                merchantId,
                paymentInternalId,
                refundAmount,
                utc(CREATED_AT.plusSeconds(3)),
                utc(CREATED_AT.plusSeconds(4))
        );
        return new RefundFixture(
                merchantId,
                paymentInternalId,
                paymentPublicId,
                refundPublicId
        );
    }

    private long insertMerchant(String publicId) {
        Long id = jdbcTemplate.queryForObject(
                """
                INSERT INTO merchants (public_id, name, status, created_at, updated_at, version)
                VALUES (?, 'Refund Finalization Store', 'ACTIVE', ?, ?, 0)
                RETURNING id
                """,
                Long.class,
                publicId,
                utc(CREATED_AT),
                utc(CREATED_AT)
        );
        return id;
    }

    private static FinalizeRefundCommand command(
            RefundFixture fixture,
            RefundProviderResult providerResult
    ) {
        return new FinalizeRefundCommand(
                fixture.merchantId(),
                fixture.refundPublicId(),
                fixture.paymentPublicId(),
                providerResult
        );
    }

    private static RefundProviderResult success(String providerRefundId) {
        return new RefundProviderResult(
                "SIMULATOR",
                RefundProviderOutcome.SUCCESS,
                providerRefundId,
                null,
                null
        );
    }

    private static RefundProviderResult failure(
            RefundProviderOutcome outcome,
            String code,
            String message
    ) {
        return new RefundProviderResult(
                "SIMULATOR",
                outcome,
                null,
                code,
                message
        );
    }

    private void assertRefund(
            RefundFixture fixture,
            RefundStatus expectedStatus,
            String expectedProviderRefundId,
            String expectedFailureCode
    ) {
        Refund refund = refundRepository.findByPublicIdAndMerchantId(
                fixture.refundPublicId(),
                fixture.merchantId()
        ).orElseThrow();
        assertThat(refund.paymentIntentId()).isEqualTo(fixture.paymentInternalId());
        assertThat(refund.status()).isEqualTo(expectedStatus);
        assertThat(refund.providerRefundId()).isEqualTo(expectedProviderRefundId);
        assertThat(refund.failureCode()).isEqualTo(expectedFailureCode);
        if (expectedStatus == RefundStatus.PROCESSING) {
            assertThat(refund.completedAt()).isNull();
        } else {
            assertThat(refund.completedAt()).isNotNull();
        }
    }

    private void assertCapacity(
            RefundFixture fixture,
            long expectedRefunded,
            long expectedReserved,
            String expectedStatus
    ) {
        Map<String, Object> payment = jdbcTemplate.queryForMap(
                """
                SELECT status, amount_minor, refunded_amount_minor,
                    refund_reserved_minor, currency
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
        assertThat(((String) payment.get("currency")).trim()).isEqualTo("USD");
    }

    private void createRefundUpdateFailureTrigger() {
        jdbcTemplate.execute("""
                CREATE OR REPLACE FUNCTION fail_refund_update() RETURNS trigger AS $$
                BEGIN
                    RAISE EXCEPTION 'forced refund update failure';
                END;
                $$ LANGUAGE plpgsql
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER fail_refund_update_trigger
                BEFORE UPDATE ON refunds
                FOR EACH ROW EXECUTE FUNCTION fail_refund_update()
                """);
    }

    private void createPaymentUpdateFailureTrigger() {
        jdbcTemplate.execute("""
                CREATE OR REPLACE FUNCTION fail_payment_update() RETURNS trigger AS $$
                BEGIN
                    RAISE EXCEPTION 'forced payment update failure';
                END;
                $$ LANGUAGE plpgsql
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER fail_payment_update_trigger
                BEFORE UPDATE ON payment_intents
                FOR EACH ROW EXECUTE FUNCTION fail_payment_update()
                """);
    }

    private void dropFailureTriggers() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS fail_refund_update_trigger ON refunds");
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS fail_refund_update()");
        jdbcTemplate.execute(
                "DROP TRIGGER IF EXISTS fail_payment_update_trigger ON payment_intents"
        );
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS fail_payment_update()");
    }

    private static java.time.OffsetDateTime utc(Instant value) {
        return value.atOffset(ZoneOffset.UTC);
    }

    private static FinalizationAttempt await(Future<FinalizationAttempt> future) {
        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new AssertionError("Concurrent finalization did not complete", exception);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting to start finalization");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting to finalize", exception);
        }
    }

    private record RefundFixture(
            long merchantId,
            long paymentInternalId,
            String paymentPublicId,
            String refundPublicId
    ) {
    }

    private record FinalizationAttempt(boolean succeeded, RuntimeException failure) {
    }
}
