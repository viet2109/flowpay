package com.flowpay.backend;

import com.flowpay.backend.common.security.MerchantApiPrincipal;
import com.flowpay.backend.idempotency.application.IdempotencyAcquisitionCommand;
import com.flowpay.backend.idempotency.application.IdempotencyAcquisitionService;
import com.flowpay.backend.idempotency.application.IdempotencyKeyReusedException;
import com.flowpay.backend.idempotency.application.IdempotencyRequestInProgressException;
import com.flowpay.backend.idempotency.domain.IdempotencyKey;
import com.flowpay.backend.idempotency.domain.IdempotencyOperation;
import com.flowpay.backend.payment.application.FinalizePaymentConfirmationCommand;
import com.flowpay.backend.payment.application.FinalizePaymentConfirmationService;
import com.flowpay.backend.payment.application.IdempotentConfirmPaymentCommand;
import com.flowpay.backend.payment.application.IdempotentConfirmPaymentService;
import com.flowpay.backend.payment.application.IdempotentCreatePaymentCommand;
import com.flowpay.backend.payment.application.IdempotentCreatePaymentService;
import com.flowpay.backend.payment.application.PaymentProviderPort;
import com.flowpay.backend.payment.application.PaymentProviderResult;
import com.flowpay.backend.payment.application.PreparePaymentConfirmationCommand;
import com.flowpay.backend.payment.application.PreparePaymentConfirmationService;
import com.flowpay.backend.payment.domain.ProviderOutcome;
import com.flowpay.backend.refund.application.FinalizeRefundCommand;
import com.flowpay.backend.refund.application.FinalizeRefundService;
import com.flowpay.backend.refund.application.IdempotentRefundCommand;
import com.flowpay.backend.refund.application.IdempotentRefundService;
import com.flowpay.backend.refund.application.PrepareRefundCommand;
import com.flowpay.backend.refund.application.PrepareRefundService;
import com.flowpay.backend.refund.application.RefundProviderPort;
import com.flowpay.backend.refund.application.RefundProviderResult;
import com.flowpay.backend.refund.domain.RefundProviderOutcome;
import com.flowpay.backend.refund.domain.RefundReason;
import com.flowpay.backend.refund.domain.RefundStatus;
import com.flowpay.backend.testing.PostgresIntegrationTest;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BusinessMetricsIntegrationTest extends PostgresIntegrationTest {

    private static final AtomicInteger EXPORT_ATTEMPTS = new AtomicInteger();
    private static final HttpServer UNAVAILABLE_COLLECTOR = unavailableCollector();
    private static final AtomicBoolean RECORDING_UNAVAILABLE = new AtomicBoolean();
    private static final AtomicBoolean FILTER_INSTALLED = new AtomicBoolean();
    private static final MerchantApiPrincipal PRINCIPAL = new MerchantApiPrincipal("mrc_metrics", "key_metrics");

    @Autowired private IdempotentCreatePaymentService creation;
    @Autowired private IdempotentConfirmPaymentService confirmation;
    @Autowired private PreparePaymentConfirmationService preparePayment;
    @Autowired private FinalizePaymentConfirmationService finalizePayment;
    @Autowired private IdempotentRefundService refunds;
    @Autowired private PrepareRefundService prepareRefund;
    @Autowired private FinalizeRefundService finalizeRefund;
    @Autowired private IdempotencyAcquisitionService acquisition;
    @Autowired private MeterRegistry registry;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoBean private PaymentProviderPort paymentProvider;
    @MockitoBean private RefundProviderPort refundProvider;

    @DynamicPropertySource
    static void collectorProperties(DynamicPropertyRegistry properties) {
        // Override even a run-wide export=false: this owned fixture proves real async exporter failure.
        properties.add("management.otlp.metrics.export.enabled", () -> true);
        properties.add("management.otlp.metrics.export.step", () -> "1s");
        properties.add("management.otlp.metrics.export.url", () ->
                "http://127.0.0.1:" + UNAVAILABLE_COLLECTOR.getAddress().getPort() + "/v1/metrics");
    }

    @BeforeEach
    void setUp() {
        RECORDING_UNAVAILABLE.set(false);
        if (FILTER_INSTALLED.compareAndSet(false, true)) {
            registry.config().meterFilter(new MeterFilter() {
                @Override
                public Meter.Id map(Meter.Id id) {
                    if (RECORDING_UNAVAILABLE.get() && id.getName().startsWith("flowpay.")) {
                        throw new IllegalStateException("synthetic-sensitive-telemetry-diagnostic");
                    }
                    return id;
                }
            });
        }
        removeBusinessMeters(registry);
        jdbc.update("""
                TRUNCATE TABLE outbox_events, ledger_entries, ledger_transactions, ledger_accounts,
                    refunds, idempotency_records, payment_transactions, payment_intents,
                    merchant_members, merchant_api_keys, refresh_tokens, merchants, users
                RESTART IDENTITY CASCADE
                """);
        jdbc.update("""
                INSERT INTO merchants (public_id, name, status, created_at, updated_at)
                VALUES ('mrc_metrics', 'Metrics fixture', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """);
        when(paymentProvider.charge(any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return paymentResult(ProviderOutcome.SUCCESS);
        });
        when(refundProvider.refund(any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return refundResult(RefundProviderOutcome.SUCCESS);
        });
    }

    @AfterEach
    void restoreRecording() {
        RECORDING_UNAVAILABLE.set(false);
    }

    @AfterAll
    static void stopOwnedCollector() {
        UNAVAILABLE_COLLECTOR.stop(0);
    }

    @ParameterizedTest
    @EnumSource(ProviderOutcome.class)
    void shouldCountCommittedPaymentOutcomeOnceWithoutProviderCallsOnReplay(ProviderOutcome outcome) {
        String payment = createPayment("create");
        when(paymentProvider.charge(any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return paymentResult(outcome);
        });
        var command = new IdempotentConfirmPaymentCommand(PRINCIPAL, IdempotencyKey.of("confirm"), payment);
        var first = confirmation.confirm(command);
        var replay = confirmation.confirm(command);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.response()).isEqualTo(first.response());
        assertThat(counter("flowpay.payment.confirmation.outcomes", "outcome", outcome.name())).isEqualTo(1);
        assertThat(timerCount("flowpay.payment.provider.duration", outcome.name())).isEqualTo(1);
        assertThat(decisions(IdempotencyOperation.PAYMENT_INTENT_CONFIRM, "NEW")).isEqualTo(1);
        assertThat(decisions(IdempotencyOperation.PAYMENT_INTENT_CONFIRM, "REPLAY")).isEqualTo(1);
        verify(paymentProvider, times(1)).charge(any());
    }

    @ParameterizedTest
    @EnumSource(RefundProviderOutcome.class)
    void shouldCountCommittedRefundOutcomeOnceAndKeepUnknownNonTerminal(RefundProviderOutcome outcome) {
        String payment = succeededPayment();
        when(refundProvider.refund(any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return refundResult(outcome);
        });
        var command = refundCommand(payment, "refund", 400);
        var first = refunds.create(command);
        var replay = refunds.create(command);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.response()).isEqualTo(first.response());
        assertThat(counter("flowpay.refund.outcomes", "outcome", outcome.name())).isEqualTo(1);
        assertThat(timerCount("flowpay.refund.provider.duration", outcome.name())).isEqualTo(1);
        assertThat(decisions(IdempotencyOperation.REFUND_CREATE, "REPLAY")).isEqualTo(1);
        assertThatThrownBy(() -> refunds.create(refundCommand(payment, "refund", 401)))
                .isInstanceOf(IdempotencyKeyReusedException.class);
        assertThat(decisions(IdempotencyOperation.REFUND_CREATE, "KEY_REUSED")).isEqualTo(1);
        verify(refundProvider, times(1)).refund(any());
        if (outcome == RefundProviderOutcome.UNKNOWN) {
            assertThat(first.response().status()).isEqualTo(RefundStatus.PROCESSING);
            assertThat(jdbc.queryForObject("SELECT refund_reserved_minor FROM payment_intents", Long.class)).isEqualTo(400);
            assertThat(jdbc.queryForObject("SELECT refunded_amount_minor FROM payment_intents", Long.class)).isZero();
        }
    }

    @ParameterizedTest
    @EnumSource(ProviderOutcome.class)
    void shouldDropRolledBackPaymentObservationsAndCountOnlyTheSubsequentCommit(ProviderOutcome outcome) {
        String payment = createPayment("create");
        var prepared = preparePayment.prepare(new PreparePaymentConfirmationCommand(PRINCIPAL, payment));
        var command = new FinalizePaymentConfirmationCommand(payment, prepared.transactionPublicId(), paymentResult(outcome));
        new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
            finalizePayment.finalizeConfirmation(command);
            assertThat(counter("flowpay.payment.confirmation.outcomes", "outcome", outcome.name())).isZero();
            transaction.setRollbackOnly();
        });
        assertThat(counter("flowpay.payment.confirmation.outcomes", "outcome", outcome.name())).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM payment_transactions", String.class)).isEqualTo("PROCESSING");
        finalizePayment.finalizeConfirmation(command);
        assertThat(counter("flowpay.payment.confirmation.outcomes", "outcome", outcome.name())).isEqualTo(1);
        assertThatThrownBy(() -> finalizePayment.finalizeConfirmation(command)).isInstanceOf(RuntimeException.class);
        assertThat(counter("flowpay.payment.confirmation.outcomes", "outcome", outcome.name())).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(RefundProviderOutcome.class)
    void shouldDropRolledBackRefundObservationsAndCapacityChanges(RefundProviderOutcome outcome) {
        String payment = succeededPayment();
        var prepared = prepareRefund.prepare(new PrepareRefundCommand(PRINCIPAL, IdempotencyKey.of("refund"), payment,
                400, RefundReason.of("synthetic-private-reason"))).prepared();
        var command = new FinalizeRefundCommand(prepared.merchantInternalId(), prepared.refundPublicId(), payment,
                refundResult(outcome));
        new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
            finalizeRefund.finalizeRefund(command);
            assertThat(counter("flowpay.refund.outcomes", "outcome", outcome.name())).isZero();
            transaction.setRollbackOnly();
        });
        assertThat(counter("flowpay.refund.outcomes", "outcome", outcome.name())).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM refunds", String.class)).isEqualTo("PROCESSING");
        assertThat(jdbc.queryForObject("SELECT refund_reserved_minor FROM payment_intents", Long.class)).isEqualTo(400);
        finalizeRefund.finalizeRefund(command);
        assertThat(counter("flowpay.refund.outcomes", "outcome", outcome.name())).isEqualTo(1);
        if (outcome == RefundProviderOutcome.UNKNOWN) {
            finalizeRefund.finalizeRefund(command);
            assertThat(counter("flowpay.refund.outcomes", "outcome", "UNKNOWN")).isEqualTo(1);
            finalizeRefund.finalizeRefund(new FinalizeRefundCommand(prepared.merchantInternalId(), prepared.refundPublicId(),
                    payment, refundResult(RefundProviderOutcome.SUCCESS)));
            assertThat(counter("flowpay.refund.outcomes", "outcome", "SUCCESS")).isEqualTo(1);
        }
    }

    @Test
    void shouldSeparateDiagnosticDecisionsFromCommittedReservationsAndObserveConflictDespiteRollback() {
        var command = creationCommand("create", 1000);
        new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
            creation.create(command);
            assertThat(counter("flowpay.idempotency.reservations", "operation", "PAYMENT_INTENT_CREATE")).isZero();
            transaction.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_records", Integer.class)).isZero();
        assertThat(counter("flowpay.idempotency.reservations", "operation", "PAYMENT_INTENT_CREATE")).isZero();
        assertThat(decisions(IdempotencyOperation.PAYMENT_INTENT_CREATE, "NEW")).isEqualTo(1);
        creation.create(command);
        assertThat(counter("flowpay.idempotency.reservations", "operation", "PAYMENT_INTENT_CREATE")).isEqualTo(1);
        assertThat(decisions(IdempotencyOperation.PAYMENT_INTENT_CREATE, "NEW")).isEqualTo(2);
        creation.create(command);
        assertThat(decisions(IdempotencyOperation.PAYMENT_INTENT_CREATE, "REPLAY")).isEqualTo(1);
        assertThatThrownBy(() -> creation.create(creationCommand("create", 1001)))
                .isInstanceOf(IdempotencyKeyReusedException.class);
        assertThat(decisions(IdempotencyOperation.PAYMENT_INTENT_CREATE, "KEY_REUSED")).isEqualTo(1);
        assertThat(counter("flowpay.idempotency.reservations", "operation", "PAYMENT_INTENT_CREATE")).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(IdempotencyOperation.class)
    void shouldObserveInProgressAndLookupMissesWithoutExtraReservations(IdempotencyOperation operation) {
        Long merchant = jdbc.queryForObject("SELECT id FROM merchants", Long.class);
        var command = new IdempotencyAcquisitionCommand(merchant, operation, IdempotencyKey.of("scope"), "a".repeat(64));
        assertThat(acquisition.findExisting(command)).isEmpty();
        acquisition.acquire(command);
        acquisition.acquire(command);
        assertThat(decisions(operation, "NOT_FOUND")).isEqualTo(1);
        assertThat(decisions(operation, "IN_PROGRESS")).isEqualTo(1);
        assertThat(counter("flowpay.idempotency.reservations", "operation", operation.name())).isEqualTo(1);
    }

    @Test
    void shouldNotChangeFinancialResultsWhenRecordingAndTheActualOtlpBackendBothFail() {
        RECORDING_UNAVAILABLE.set(true);
        String payment = succeededPayment();
        var refund = refunds.create(refundCommand(payment, "refund", 400));
        assertThat(refund.response().status()).isEqualTo(RefundStatus.SUCCEEDED);
        assertThat(jdbc.queryForObject("SELECT refunded_amount_minor FROM payment_intents", Long.class)).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events", Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_records WHERE status = 'COMPLETED'", Integer.class))
                .isEqualTo(3);
        assertThat(registry.getMeters()).noneMatch(meter -> meter.getId().getName().startsWith("flowpay."));
        await().atMost(Duration.ofSeconds(10)).until(() -> EXPORT_ATTEMPTS.get() > 0);
    }

    @Test
    void shouldMeasureThrownProviderAttemptsWithoutInventingPersistedFailureOutcomes() {
        String payment = createPayment("first");
        RuntimeException failure = new RuntimeException("synthetic-sensitive-provider-diagnostic");
        when(paymentProvider.charge(any())).thenThrow(failure);
        assertThatThrownBy(() -> confirmation.confirm(new IdempotentConfirmPaymentCommand(PRINCIPAL,
                IdempotencyKey.of("first-confirm"), payment))).isSameAs(failure);
        assertThat(timerCount("flowpay.payment.provider.duration", "EXCEPTION")).isEqualTo(1);
        assertThat(counter("flowpay.payment.confirmation.outcomes", "outcome", "TECHNICAL_FAILURE")).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM payment_intents", String.class)).isEqualTo("PROCESSING");
        doReturn(paymentResult(ProviderOutcome.SUCCESS)).when(paymentProvider).charge(any());
        String succeeded = succeededPayment();
        when(refundProvider.refund(any())).thenThrow(failure);
        assertThatThrownBy(() -> refunds.create(refundCommand(succeeded, "refund", 400))).isSameAs(failure);
        assertThat(timerCount("flowpay.refund.provider.duration", "EXCEPTION")).isEqualTo(1);
        assertThat(counter("flowpay.refund.outcomes", "outcome", "TECHNICAL_FAILURE")).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM refunds", String.class)).isEqualTo("PROCESSING");
    }

    @Test
    void shouldCountOneLogicalOutcomeAndProviderAttemptForConcurrentSameKeyConfirmation() throws Exception {
        String payment = createPayment("create");
        var command = new IdempotentConfirmPaymentCommand(PRINCIPAL, IdempotencyKey.of("concurrent"), payment);
        CountDownLatch enteredProvider = new CountDownLatch(1);
        CountDownLatch releaseProvider = new CountDownLatch(1);
        when(paymentProvider.charge(any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            enteredProvider.countDown();
            assertThat(releaseProvider.await(10, TimeUnit.SECONDS)).isTrue();
            return paymentResult(ProviderOutcome.SUCCESS);
        });
        try (var executor = Executors.newSingleThreadExecutor()) {
            var first = executor.submit(() -> confirmation.confirm(command));
            try {
                assertThat(enteredProvider.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> confirmation.confirm(command)).isInstanceOf(IdempotencyRequestInProgressException.class);
                assertThat(decisions(IdempotencyOperation.PAYMENT_INTENT_CONFIRM, "IN_PROGRESS")).isEqualTo(1);
            } finally {
                releaseProvider.countDown();
            }
            assertThat(first.get(10, TimeUnit.SECONDS).replayed()).isFalse();
        }
        assertThat(confirmation.confirm(command).replayed()).isTrue();
        assertThat(counter("flowpay.payment.confirmation.outcomes", "outcome", "SUCCESS")).isEqualTo(1);
        assertThat(timerCount("flowpay.payment.provider.duration", "SUCCESS")).isEqualTo(1);
        assertThat(counter("flowpay.idempotency.reservations", "operation", "PAYMENT_INTENT_CONFIRM")).isEqualTo(1);
        verify(paymentProvider, times(1)).charge(any());
    }

    private String createPayment(String key) {
        return creation.create(creationCommand(key, 1000)).response().id();
    }

    private String succeededPayment() {
        String payment = createPayment("succeeded-create");
        confirmation.confirm(new IdempotentConfirmPaymentCommand(PRINCIPAL, IdempotencyKey.of("succeeded-confirm"), payment));
        return payment;
    }

    private static IdempotentCreatePaymentCommand creationCommand(String key, long amount) {
        return new IdempotentCreatePaymentCommand(PRINCIPAL, IdempotencyKey.of(key), amount, "USD",
                "synthetic-private-order-" + key, "synthetic-private-description");
    }

    private static IdempotentRefundCommand refundCommand(String payment, String key, long amount) {
        return new IdempotentRefundCommand(PRINCIPAL, IdempotencyKey.of(key), payment, amount,
                RefundReason.of("synthetic-private-reason"));
    }

    private double counter(String name, String... tags) {
        var meter = registry.find(name).tags(tags).counter();
        return meter == null ? 0 : meter.count();
    }

    private double decisions(IdempotencyOperation operation, String decision) {
        return counter("flowpay.idempotency.decisions", "operation", operation.name(), "decision", decision);
    }

    private long timerCount(String name, String outcome) {
        var meter = registry.find(name).tag("outcome", outcome).timer();
        return meter == null ? 0 : meter.count();
    }

    private static PaymentProviderResult paymentResult(ProviderOutcome outcome) {
        return new PaymentProviderResult("SIMULATOR", outcome, "synthetic-private-provider-id",
                outcome == ProviderOutcome.SUCCESS ? null : "SYNTHETIC_CODE",
                outcome == ProviderOutcome.SUCCESS ? null : "synthetic-private-provider-message");
    }

    private static RefundProviderResult refundResult(RefundProviderOutcome outcome) {
        return new RefundProviderResult("SIMULATOR", outcome, "synthetic-private-refund-id",
                outcome == RefundProviderOutcome.SUCCESS ? null : "SYNTHETIC_CODE",
                outcome == RefundProviderOutcome.SUCCESS ? null : "synthetic-private-provider-message");
    }

    private static void removeBusinessMeters(MeterRegistry registry) {
        if (registry instanceof CompositeMeterRegistry composite) {
            composite.getRegistries().forEach(BusinessMetricsIntegrationTest::removeBusinessMeters);
        }
        registry.getMeters().stream().filter(meter -> meter.getId().getName().startsWith("flowpay."))
                .toList().forEach(registry::remove);
    }

    private static HttpServer unavailableCollector() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/metrics", exchange -> {
                EXPORT_ATTEMPTS.incrementAndGet();
                exchange.getRequestBody().transferTo(java.io.OutputStream.nullOutputStream());
                exchange.sendResponseHeaders(503, -1);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException exception) {
            throw new IllegalStateException("Could not start owned unavailable-collector fixture");
        }
    }
}
