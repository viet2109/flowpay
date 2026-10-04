package com.flowpay.backend.infrastructure.messaging.outbox;

import com.flowpay.backend.common.security.MerchantApiPrincipal;
import com.flowpay.backend.idempotency.domain.IdempotencyKey;
import com.flowpay.backend.idempotency.application.IdempotencyKeyReusedException;
import com.flowpay.backend.payment.application.ConfirmPaymentCommand;
import com.flowpay.backend.payment.application.ConfirmPaymentService;
import com.flowpay.backend.payment.application.FinalizePaymentConfirmationCommand;
import com.flowpay.backend.payment.application.FinalizePaymentConfirmationService;
import com.flowpay.backend.payment.application.PaymentProviderPort;
import com.flowpay.backend.payment.application.PaymentProviderResult;
import com.flowpay.backend.payment.application.PreparePaymentConfirmationCommand;
import com.flowpay.backend.payment.application.PreparePaymentConfirmationService;
import com.flowpay.backend.payment.domain.ProviderOutcome;
import com.flowpay.backend.refund.application.IdempotentRefundCommand;
import com.flowpay.backend.refund.application.IdempotentRefundService;
import com.flowpay.backend.refund.application.PrepareRefundCommand;
import com.flowpay.backend.refund.application.PrepareRefundService;
import com.flowpay.backend.refund.application.RefundProviderPort;
import com.flowpay.backend.refund.application.RefundProviderResult;
import com.flowpay.backend.refund.domain.RefundProviderOutcome;
import com.flowpay.backend.refund.domain.RefundReason;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
@Import(SourceEventLifecycleIntegrationTest.FixedClock.class)
class SourceEventLifecycleIntegrationTest extends PostgresIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-02T09:00:00.123456Z");
    private static final Instant TRANSITION = NOW.truncatedTo(ChronoUnit.MICROS);
    private static final MerchantApiPrincipal PRINCIPAL = new MerchantApiPrincipal("mrc_source_events", "key_source");

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;
    @Autowired private PreparePaymentConfirmationService preparePayment;
    @Autowired private FinalizePaymentConfirmationService finalizePayment;
    @Autowired private ConfirmPaymentService confirmPayment;
    @Autowired private PrepareRefundService prepareRefund;
    @Autowired private IdempotentRefundService refundService;
    @MockitoBean private PaymentProviderPort paymentProvider;
    @MockitoBean private RefundProviderPort refundProvider;
    private long merchantId;

    @BeforeEach
    void setup() {
        dropTrigger();
        jdbc.execute("""
                TRUNCATE TABLE outbox_events, ledger_entries, ledger_transactions,
                    ledger_accounts, merchants, users RESTART IDENTITY CASCADE
                """);
        merchantId = jdbc.queryForObject("""
                INSERT INTO merchants (public_id, name, status, created_at, updated_at)
                VALUES ('mrc_source_events', 'Source events', 'ACTIVE', ?, ?) RETURNING id
                """, Long.class, oldTime(), oldTime());
    }

    @AfterEach
    void cleanup() {
        dropTrigger();
    }

    @ParameterizedTest
    @EnumSource(ProviderOutcome.class)
    void paymentPublishesExactSourceFactsAndOnlyKnownTerminalOutcomes(ProviderOutcome outcome) {
        insertPayment(false);
        var prepared = preparePayment.prepare(new PreparePaymentConfirmationCommand(PRINCIPAL, "pi_source"));
        assertEvent("payment.processing.v1", "pi_source", false, false);
        assertTimestamp("payment_intents", "updated_at", TRANSITION);
        assertTimestamp("payment_transactions", "started_at", TRANSITION);
        var result = finalizePayment.finalizeConfirmation(new FinalizePaymentConfirmationCommand(
                "pi_source", prepared.transactionPublicId(), paymentResult(outcome)));
        List<String> expected = outcome == ProviderOutcome.UNKNOWN
                ? List.of("payment.processing.v1")
                : List.of("payment.processing.v1", outcome == ProviderOutcome.SUCCESS
                        ? "payment.succeeded.v1" : "payment.failed.v1");
        assertThat(eventTypes()).containsExactlyInAnyOrderElementsOf(expected);
        if (outcome != ProviderOutcome.UNKNOWN) {
            assertEvent(outcome == ProviderOutcome.SUCCESS ? "payment.succeeded.v1" : "payment.failed.v1",
                    "pi_source", false, outcome != ProviderOutcome.SUCCESS);
            assertTimestamp("payment_transactions", "completed_at", TRANSITION);
        }
        assertThat(result.paymentStatus().name()).isEqualTo(outcome == ProviderOutcome.UNKNOWN
                ? "PROCESSING" : outcome == ProviderOutcome.SUCCESS ? "SUCCEEDED" : "FAILED");
        assertThatThrownBy(() -> preparePayment.prepare(
                new PreparePaymentConfirmationCommand(PRINCIPAL, "pi_source"))).isInstanceOf(RuntimeException.class);
        assertThat(eventTypes()).hasSize(expected.size());
        verifyNoInteractions(paymentProvider);
        assertNoLedger();
    }

    @ParameterizedTest
    @EnumSource(RefundProviderOutcome.class)
    void refundPublishesNewProcessingAndKnownTerminalEventsButNothingOnReplayOrConflicts(
            RefundProviderOutcome outcome
    ) {
        insertPayment(true);
        when(refundProvider.refund(any())).thenReturn(refundResult(outcome));
        var command = refundCommand(400);
        var result = refundService.create(command);
        String refundId = jdbc.queryForObject("SELECT public_id FROM refunds", String.class);
        assertEvent("refund.processing.v1", refundId, true, false);
        assertTimestamp("refunds", "created_at", TRANSITION);
        int expectedCount = outcome == RefundProviderOutcome.UNKNOWN ? 1 : 2;
        assertThat(eventTypes()).hasSize(expectedCount);
        if (outcome != RefundProviderOutcome.UNKNOWN) {
            assertEvent(outcome == RefundProviderOutcome.SUCCESS ? "refund.succeeded.v1" : "refund.failed.v1",
                    refundId, true, outcome != RefundProviderOutcome.SUCCESS);
            assertTimestamp("refunds", "completed_at", TRANSITION);
        }
        assertThat(refundService.create(command).response()).isEqualTo(result.response());
        assertThatThrownBy(() -> refundService.create(refundCommand(401)))
                .isInstanceOf(IdempotencyKeyReusedException.class);
        assertThat(eventTypes()).hasSize(expectedCount);
        verify(refundProvider, times(1)).refund(any());
        assertNoLedger();
    }

    @Test
    void refundInProgressAndKeyReusedNeverEmitAnotherProcessingEvent() {
        insertPayment(true);
        PrepareRefundCommand command = new PrepareRefundCommand(PRINCIPAL, IdempotencyKey.of("source-refund"),
                "pi_source", 400, RefundReason.of("Customer request"));
        prepareRefund.prepare(command);
        assertThat(prepareRefund.prepare(command).decision().name()).isEqualTo("IN_PROGRESS");
        assertThat(prepareRefund.prepare(new PrepareRefundCommand(PRINCIPAL, command.idempotencyKey(),
                "pi_source", 401, command.reason())).decision().name()).isEqualTo("KEY_REUSED");
        assertThat(eventTypes()).containsExactly("refund.processing.v1");
        verifyNoInteractions(refundProvider);
    }

    @Test
    void paymentProcessingOutboxFailureRollsBackIntentAndAttemptAndNeverCallsProvider() {
        insertPayment(false);
        failEvent("payment.processing.v1");
        assertThatThrownBy(() -> confirmPayment.confirm(new ConfirmPaymentCommand(PRINCIPAL, "pi_source")))
                .isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM payment_intents", String.class)).isEqualTo("CREATED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_transactions", Long.class)).isZero();
        assertThat(eventTypes()).isEmpty();
        verifyNoInteractions(paymentProvider);
    }

    @Test
    void refundProcessingOutboxFailureRollsBackAcquisitionReservationAndRefundAndNeverCallsProvider() {
        insertPayment(true);
        failEvent("refund.processing.v1");
        assertThatThrownBy(() -> refundService.create(refundCommand(400))).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT refund_reserved_minor FROM payment_intents", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refunds", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_records", Long.class)).isZero();
        assertThat(eventTypes()).isEmpty();
        verifyNoInteractions(refundProvider);
    }

    @ParameterizedTest
    @EnumSource(value = ProviderOutcome.class, names = {"DECLINED", "TECHNICAL_FAILURE"})
    void failedPaymentOutboxFailureRollsBackKnownFailureFinalization(ProviderOutcome outcome) {
        insertPayment(false);
        var prepared = preparePayment.prepare(new PreparePaymentConfirmationCommand(PRINCIPAL, "pi_source"));
        failEvent("payment.failed.v1");
        assertThatThrownBy(() -> finalizePayment.finalizeConfirmation(new FinalizePaymentConfirmationCommand(
                "pi_source", prepared.transactionPublicId(), paymentResult(outcome)))).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM payment_intents", String.class)).isEqualTo("PROCESSING");
        assertThat(jdbc.queryForObject("SELECT status FROM payment_transactions", String.class)).isEqualTo("PROCESSING");
        assertThat(eventTypes()).containsExactly("payment.processing.v1");
    }

    @ParameterizedTest
    @EnumSource(value = RefundProviderOutcome.class, names = {"DECLINED", "TECHNICAL_FAILURE"})
    void failedRefundOutboxFailureRollsBackFailureAndReservationRelease(RefundProviderOutcome outcome) {
        insertPayment(true);
        when(refundProvider.refund(any())).thenReturn(refundResult(outcome));
        failEvent("refund.failed.v1");
        assertThatThrownBy(() -> refundService.create(refundCommand(400))).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM refunds", String.class)).isEqualTo("PROCESSING");
        assertThat(jdbc.queryForObject("SELECT refund_reserved_minor FROM payment_intents", Long.class)).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT refunded_amount_minor FROM payment_intents", Long.class)).isZero();
        assertThat(eventTypes()).containsExactly("refund.processing.v1");
    }

    private void assertEvent(String type, String aggregateId, boolean refund, boolean failed) {
        var row = jdbc.queryForMap("""
                SELECT aggregate_type, aggregate_id, occurred_at, payload::text AS payload
                FROM outbox_events WHERE event_type = ?
                """, type);
        assertThat(row.get("aggregate_id")).isEqualTo(aggregateId);
        assertThat(row.get("aggregate_type")).isEqualTo(refund ? "REFUND" : "PAYMENT_INTENT");
        assertThat(jdbc.queryForObject("SELECT occurred_at FROM outbox_events WHERE event_type = ?",
                OffsetDateTime.class, type).toInstant()).isEqualTo(TRANSITION);
        JsonNode payload = json.readTree((String) row.get("payload"));
        var expected = new java.util.HashSet<>(Set.of(
                "merchantInternalId", "paymentPublicId", "amountMinor", "currency", "occurredAt"));
        if (refund) expected.add("refundPublicId");
        if (failed) expected.addAll(Set.of("failureCode", "failureMessage"));
        assertThat(payload.propertyNames()).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(payload.path("merchantInternalId").longValue()).isEqualTo(merchantId);
        assertThat(payload.path("paymentPublicId").stringValue()).isEqualTo("pi_source");
        assertThat(payload.path("amountMinor").longValue()).isEqualTo(refund ? 400 : 1000);
        assertThat(payload.path("currency").stringValue()).isEqualTo("VND");
        assertThat(Instant.parse(payload.path("occurredAt").stringValue())).isEqualTo(TRANSITION);
        if (refund) assertThat(payload.path("refundPublicId").stringValue()).isEqualTo(aggregateId);
        if (failed) {
            assertThat(payload.path("failureCode").stringValue()).isEqualTo("PROVIDER_UNAVAILABLE");
            assertThat(payload.path("failureMessage").stringValue()).isEqualTo("The provider operation failed.");
        }
        assertThat((String) row.get("payload")).doesNotContain("providerTransactionId", "providerRefundId",
                "idempotencyKey", "requestHash", "secret", "version", "rawResponse");
    }

    private void assertTimestamp(String table, String column, Instant expected) {
        assertThat(jdbc.queryForObject("SELECT " + column + " FROM " + table, OffsetDateTime.class).toInstant())
                .isEqualTo(expected);
    }

    private void insertPayment(boolean succeeded) {
        long paymentId = jdbc.queryForObject("""
                INSERT INTO payment_intents (public_id, merchant_id, amount_minor, currency, status, created_at, updated_at)
                VALUES ('pi_source', ?, 1000, 'VND', ?, ?, ?) RETURNING id
                """, Long.class, merchantId, succeeded ? "SUCCEEDED" : "CREATED", oldTime(), oldTime());
        if (succeeded) {
            jdbc.update("""
                    INSERT INTO payment_transactions (
                        public_id, payment_intent_id, attempt_no, provider, provider_transaction_id,
                        status, created_at, updated_at, started_at, completed_at)
                    VALUES ('ptxn_source', ?, 1, 'SIMULATOR', 'sim_source', 'SUCCEEDED', ?, ?, ?, ?)
                    """, paymentId, oldTime(), oldTime(), oldTime(), oldTime());
        }
    }

    private List<String> eventTypes() {
        return jdbc.queryForList("SELECT event_type FROM outbox_events ORDER BY id", String.class);
    }

    private static PaymentProviderResult paymentResult(ProviderOutcome outcome) {
        return new PaymentProviderResult("SIMULATOR", outcome, outcome == ProviderOutcome.SUCCESS ? "sim_source" : null,
                outcome == ProviderOutcome.SUCCESS ? null : "PROVIDER_UNAVAILABLE",
                outcome == ProviderOutcome.SUCCESS ? null : "The provider operation failed.");
    }

    private static RefundProviderResult refundResult(RefundProviderOutcome outcome) {
        return new RefundProviderResult("SIMULATOR", outcome, outcome == RefundProviderOutcome.SUCCESS ? "sim_refund" : null,
                outcome == RefundProviderOutcome.SUCCESS ? null : "PROVIDER_UNAVAILABLE",
                outcome == RefundProviderOutcome.SUCCESS ? null : "The provider operation failed.");
    }

    private static IdempotentRefundCommand refundCommand(long amount) {
        return new IdempotentRefundCommand(PRINCIPAL, IdempotencyKey.of("source-refund"), "pi_source",
                amount, RefundReason.of("Customer request"));
    }

    private static OffsetDateTime oldTime() {
        return NOW.minusSeconds(60).atOffset(ZoneOffset.UTC);
    }

    private void failEvent(String type) {
        jdbc.execute("""
                CREATE FUNCTION fail_source_event() RETURNS trigger AS $$
                BEGIN
                    IF NEW.event_type = '%s' THEN
                        RAISE EXCEPTION 'forced source event failure';
                    END IF;
                    RETURN NEW;
                END;
                $$ LANGUAGE plpgsql
                """.formatted(type));
        jdbc.execute("""
                CREATE TRIGGER fail_source_event_trigger BEFORE INSERT ON outbox_events
                FOR EACH ROW EXECUTE FUNCTION fail_source_event()
                """);
    }

    private void dropTrigger() {
        jdbc.execute("DROP TRIGGER IF EXISTS fail_source_event_trigger ON outbox_events");
        jdbc.execute("DROP FUNCTION IF EXISTS fail_source_event()");
    }

    private void assertNoLedger() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger_transactions", Long.class)).isZero();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClock {
        @Bean
        @Primary
        Clock sourceEventClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }
}
