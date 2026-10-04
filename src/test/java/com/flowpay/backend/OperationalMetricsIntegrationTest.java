package com.flowpay.backend;

import com.flowpay.backend.common.money.Money;
import com.flowpay.backend.common.observability.MetricsRecorder;
import com.flowpay.backend.common.observability.MetricsSamplingProperties;
import com.flowpay.backend.infrastructure.messaging.IntegrationEventPublicationResult;
import com.flowpay.backend.infrastructure.messaging.IntegrationEventPublicationStatus;
import com.flowpay.backend.infrastructure.messaging.IntegrationEventTransportPublisher;
import com.flowpay.backend.infrastructure.messaging.outbox.OutboxRepository;
import com.flowpay.backend.infrastructure.messaging.outbox.metrics.OutboxMetricsProjection;
import com.flowpay.backend.infrastructure.messaging.outbox.metrics.OutboxOperationalMetrics;
import com.flowpay.backend.infrastructure.messaging.outbox.relay.OutboxRelayPersistenceService;
import com.flowpay.backend.infrastructure.messaging.outbox.relay.OutboxRelayService;
import com.flowpay.backend.ledger.application.LedgerPostingApi;
import com.flowpay.backend.ledger.application.LedgerPostingConflictException;
import com.flowpay.backend.ledger.application.PostPaymentSucceededCommand;
import com.flowpay.backend.testing.PostgresIntegrationTest;
import com.flowpay.backend.webhook.application.ClaimedWebhookDelivery;
import com.flowpay.backend.webhook.application.WebhookDeliveryAttemptRepository;
import com.flowpay.backend.webhook.application.WebhookDeliveryExecutionService;
import com.flowpay.backend.webhook.application.WebhookDeliveryWorker;
import com.flowpay.backend.webhook.application.WebhookDeliveryWorkerProperties;
import com.flowpay.backend.webhook.application.WebhookHttpClientPort;
import com.flowpay.backend.webhook.application.WebhookHttpDeliveryResult;
import com.flowpay.backend.webhook.application.WebhookMetrics;
import com.flowpay.backend.webhook.application.WebhookMetricsProjection;
import com.flowpay.backend.webhook.application.WebhookSecretCipher;
import com.flowpay.backend.webhook.infrastructure.metrics.WebhookOperationalMetrics;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Currency;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OperationalMetricsIntegrationTest extends PostgresIntegrationTest {
    private static final AtomicBoolean FILTER_INSTALLED = new AtomicBoolean();
    private static final AtomicBoolean RECORDING_UNAVAILABLE = new AtomicBoolean();
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MeterRegistry registry;
    @Autowired private MetricsRecorder recorder;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private OutboxMetricsProjection outboxProjection;
    @Autowired private WebhookMetricsProjection webhookProjection;
    @Autowired private OutboxRelayPersistenceService outboxPersistence;
    @Autowired private OutboxRelayService relay;
    @Autowired private LedgerPostingApi ledger;
    @Autowired private WebhookDeliveryExecutionService execution;
    @Autowired private WebhookSecretCipher cipher;
    @Autowired private WebhookMetrics webhookMetrics;
    @MockitoBean private IntegrationEventTransportPublisher transport;
    @MockitoBean private WebhookHttpClientPort http;
    @MockitoSpyBean private WebhookDeliveryAttemptRepository attempts;
    @MockitoSpyBean private OutboxRepository outboxRepository;
    private long merchant;

    @BeforeEach
    void setUp() {
        RECORDING_UNAVAILABLE.set(false);
        if (FILTER_INSTALLED.compareAndSet(false, true)) {
            registry.config().meterFilter(new MeterFilter() {
                @Override public Meter.Id map(Meter.Id id) {
                    if (RECORDING_UNAVAILABLE.get() && id.getName().startsWith("flowpay.")) {
                        throw new IllegalStateException("synthetic sensitive recording failure");
                    }
                    return id;
                }
            });
        }
        removeCounters(registry);
        jdbc.execute("TRUNCATE TABLE merchants, users, ledger_accounts, ledger_transactions, ledger_entries RESTART IDENTITY CASCADE");
        jdbc.execute("TRUNCATE TABLE outbox_events RESTART IDENTITY");
        merchant = jdbc.queryForObject("""
                INSERT INTO merchants(public_id, name, status, created_at, updated_at)
                VALUES ('mrc_metrics_async', 'Metrics fixture', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP) RETURNING id
                """, Long.class);
    }

    @AfterEach
    void restoreRecording() { RECORDING_UNAVAILABLE.set(false); }

    @Test
    void shouldSampleExactDurableCountsAndAgesOnceWithoutQueriesDuringScrapes() {
        outbox("pending", "PENDING", -120);
        outbox("failed", "FAILED", -60);
        outbox("published", "PUBLISHED", -900);
        var due = delivery("PENDING", -90);
        delivery("PENDING", 120);
        delivery("RETRYING", -30);
        delivery("DELIVERING", 0);
        delivery("DEAD", 0);
        delivery("DELIVERED", 0);
        jdbc.update("UPDATE webhook_endpoints SET status='DISABLED' WHERE id=?", due.endpointId());
        var firstRegistry = new SimpleMeterRegistry();
        var secondRegistry = new SimpleMeterRegistry();
        try {
            var properties = new MetricsSamplingProperties(false, Duration.ofSeconds(30));
            var outboxFirst = new OutboxOperationalMetrics(outboxProjection, new MetricsRecorder(firstRegistry), properties);
            var webhookFirst = new WebhookOperationalMetrics(webhookProjection, new MetricsRecorder(firstRegistry), properties);
            var outboxSecond = new OutboxOperationalMetrics(outboxProjection, new MetricsRecorder(secondRegistry), properties);
            assertThat(firstRegistry.get("flowpay.outbox.backlog").tag("status", "PENDING").gauge().value()).isNaN();
            outboxFirst.refresh(); webhookFirst.refresh(); outboxSecond.refresh();
            assertThat(firstRegistry.get("flowpay.outbox.backlog").tag("status", "PENDING").gauge().value()).isEqualTo(1);
            assertThat(firstRegistry.get("flowpay.outbox.backlog").tag("status", "FAILED").gauge().value()).isEqualTo(1);
            assertThat(firstRegistry.get("flowpay.outbox.oldest.unpublished.age").gauge().value()).isBetween(120.0, 130.0);
            assertThat(firstRegistry.get("flowpay.webhook.backlog").tag("status", "PENDING").gauge().value()).isEqualTo(2);
            for (String status : new String[] { "RETRYING", "DELIVERING", "DEAD" }) {
                assertThat(firstRegistry.get("flowpay.webhook.backlog").tag("status", status).gauge().value()).isEqualTo(1);
            }
            assertThat(firstRegistry.get("flowpay.webhook.oldest.due.age").gauge().value()).isBetween(90.0, 100.0);
            assertThat(secondRegistry.get("flowpay.outbox.backlog").tag("status", "PENDING").gauge().value()).isEqualTo(1);
            jdbc.update("UPDATE outbox_events SET status='PUBLISHED', published_at=CURRENT_TIMESTAMP");
            for (int scrape = 0; scrape < 100; scrape++) {
                outboxFirst.refresh();
                assertThat(firstRegistry.get("flowpay.outbox.backlog").tag("status", "PENDING").gauge().value()).isEqualTo(1);
            }
            assertThat(firstRegistry.get("flowpay.metrics.sampling.duration").tag("component", "OUTBOX").timer().count()).isEqualTo(1);
        } finally { firstRegistry.close(); secondRegistry.close(); }
    }

    @Test
    void shouldReturnEmptyAndNonNegativeProjectionAges() {
        assertThat(outboxProjection.snapshot().oldestUnpublishedAgeSeconds()).isZero();
        assertThat(webhookProjection.snapshot().oldestDueAgeSeconds()).isZero();
        outbox("future", "PENDING", 100);
        delivery("PENDING", 100);
        assertThat(outboxProjection.snapshot().oldestUnpublishedAgeSeconds()).isZero();
        assertThat(webhookProjection.snapshot().oldestDueAgeSeconds()).isZero();
    }

    @Test
    void shouldBoundAProjectionBlockedByDdlWithoutChangingBusinessRows() throws Exception {
        outbox("locked", "PENDING", -1);
        try (var connection = jdbc.getDataSource().getConnection(); var statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute("LOCK TABLE outbox_events IN ACCESS EXCLUSIVE MODE");
            long started = System.nanoTime();
            try {
                assertThatThrownBy(outboxProjection::snapshot).isInstanceOf(DataAccessException.class);
                assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(8));
            } finally { connection.rollback(); }
        }
        assertThat(outboxProjection.snapshot().pending()).isEqualTo(1);
    }

    @Test
    void shouldCountPublishedOnlyAfterCommitAndSuppressConditionalDuplicates() {
        outbox("publication", "PENDING", -1);
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            assertThat(outboxPersistence.markPublished("ievt_publication", Instant.now())).isTrue();
            assertThat(count("flowpay.outbox.published")).isZero();
            tx.setRollbackOnly();
        });
        assertThat(count("flowpay.outbox.published")).isZero();
        assertThat(outboxPersistence.markPublished("ievt_publication", Instant.now())).isTrue();
        assertThat(outboxPersistence.markPublished("ievt_publication", Instant.now())).isFalse();
        assertThat(count("flowpay.outbox.published")).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(IntegrationEventPublicationStatus.class)
    void shouldObserveTransportResultSeparatelyFromPersistedPublication(IntegrationEventPublicationStatus outcome) {
        outbox("relay", "PENDING", -1);
        when(transport.publish(any())).thenReturn(IntegrationEventPublicationResult.of(outcome));
        relay.relayDueEvents();
        assertThat(count("flowpay.outbox.publication.attempts", "outcome", outcome.name())).isEqualTo(1);
        assertThat(count("flowpay.outbox.published")).isEqualTo(outcome == IntegrationEventPublicationStatus.CONFIRMED ? 1 : 0);
        assertThat(jdbc.queryForObject("SELECT status FROM outbox_events", String.class))
                .isEqualTo(outcome == IntegrationEventPublicationStatus.CONFIRMED ? "PUBLISHED" : "FAILED");
    }

    @Test
    void shouldObserveRelayExceptionWithoutInventingPublication() {
        outbox("throwing", "PENDING", -1);
        when(transport.publish(any())).thenThrow(new IllegalStateException("sensitive broker URL"));
        relay.relayDueEvents();
        assertThat(count("flowpay.outbox.publication.attempts", "outcome", "EXCEPTION")).isEqualTo(1);
        assertThat(count("flowpay.outbox.published")).isZero();
    }

    @Test
    void shouldNotCountConfirmedButRolledBackPublicationAsPublished() {
        outbox("relay_rollback", "PENDING", -1);
        when(transport.publish(any())).thenReturn(IntegrationEventPublicationResult.of(IntegrationEventPublicationStatus.CONFIRMED));
        doAnswer(invocation -> { var result = invocation.callRealMethod(); rollbackBeforeCommit(); return result; })
                .when(outboxRepository).markPublished(any(), any());
        relay.relayDueEvents();
        assertThat(count("flowpay.outbox.publication.attempts", "outcome", "CONFIRMED")).isEqualTo(1);
        assertThat(count("flowpay.outbox.published")).isZero();
        assertThat(outboxProjection.snapshot().pending()).isEqualTo(1);
    }

    @Test
    void shouldKeepAllAsynchronousEffectsWhenRegistryCannotRecord() {
        RECORDING_UNAVAILABLE.set(true);
        outbox("telemetry_outage", "PENDING", -1);
        when(transport.publish(any())).thenReturn(IntegrationEventPublicationResult.of(IntegrationEventPublicationStatus.CONFIRMED));
        relay.relayDueEvents();
        new TransactionTemplate(transactions).executeWithoutResult(tx -> ledger.postPaymentSucceeded(paymentCommand(100)));
        delivery("PENDING", -1);
        when(http.send(any())).thenReturn(WebhookHttpDeliveryResult.http(204, 10));
        worker().deliverBatch();
        assertThat(jdbc.queryForObject("SELECT status FROM outbox_events", String.class)).isEqualTo("PUBLISHED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_transactions", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM webhook_deliveries", String.class)).isEqualTo("DELIVERED");
        assertThat(count("flowpay.outbox.published")).isZero();
        assertThat(count("flowpay.ledger.postings", "type", "PAYMENT_SUCCEEDED")).isZero();
        assertThat(count("flowpay.webhook.delivered")).isZero();
    }

    @Test
    void shouldCountOnlyOneCommittedLedgerPostingUnderConcurrentRedelivery() throws Exception {
        var release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var work = (java.util.concurrent.Callable<Object>) () -> {
                assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                return new TransactionTemplate(transactions).execute(tx -> ledger.postPaymentSucceeded(paymentCommand(100)));
            };
            var first = executor.submit(work);
            var second = executor.submit(work);
            release.countDown();
            first.get(10, TimeUnit.SECONDS); second.get(10, TimeUnit.SECONDS);
        }
        assertThat(count("flowpay.ledger.postings", "type", "PAYMENT_SUCCEEDED")).isEqualTo(1);
        assertThat(count("flowpay.ledger.posting.decisions", "type", "PAYMENT_SUCCEEDED", "outcome", "DUPLICATE")).isEqualTo(1);
    }

    @Test
    void shouldSeparateLedgerRollbackDuplicateAndConflictFromNewPostings() {
        var command = paymentCommand(100);
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            ledger.postPaymentSucceeded(command);
            assertThat(count("flowpay.ledger.postings", "type", "PAYMENT_SUCCEEDED")).isZero();
            tx.setRollbackOnly();
        });
        assertThat(count("flowpay.ledger.postings", "type", "PAYMENT_SUCCEEDED")).isZero();
        new TransactionTemplate(transactions).executeWithoutResult(tx -> ledger.postPaymentSucceeded(command));
        new TransactionTemplate(transactions).executeWithoutResult(tx -> ledger.postPaymentSucceeded(command));
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> ledger.postPaymentSucceeded(paymentCommand(101))))
                .isInstanceOf(LedgerPostingConflictException.class);
        assertThat(count("flowpay.ledger.postings", "type", "PAYMENT_SUCCEEDED")).isEqualTo(1);
        assertThat(count("flowpay.ledger.posting.decisions", "type", "PAYMENT_SUCCEEDED", "outcome", "DUPLICATE")).isEqualTo(1);
        assertThat(count("flowpay.ledger.posting.decisions", "type", "PAYMENT_SUCCEEDED", "outcome", "CONFLICT")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_transactions", Long.class)).isEqualTo(1);
    }

    @Test
    void shouldFenceWebhookFinalizationAndRollbackAfterMetricCallbackRegistration() {
        var delivery = delivery("PENDING", -1);
        doAnswer(invocation -> { var result = invocation.callRealMethod(); rollbackBeforeCommit(); return result; }).when(attempts).insert(any());
        assertThatThrownBy(() -> execution.claim(delivery.deliveryId(), delivery.endpointId())).isInstanceOf(RuntimeException.class);
        assertThat(count("flowpay.webhook.delivery.attempts")).isZero();
        doCallRealMethod().when(attempts).insert(any());
        var claim = execution.claim(delivery.deliveryId(), delivery.endpointId()).orElseThrow();
        assertThat(count("flowpay.webhook.delivery.attempts")).isEqualTo(1);
        doAnswer(invocation -> { var result = invocation.callRealMethod(); rollbackBeforeCommit(); return result; }).when(attempts).complete(any());
        assertThatThrownBy(() -> execution.finalizeResult(claim, WebhookHttpDeliveryResult.http(204, 10))).isInstanceOf(RuntimeException.class);
        assertThat(count("flowpay.webhook.delivered")).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM webhook_deliveries", String.class)).isEqualTo("DELIVERING");
        doCallRealMethod().when(attempts).complete(any());
        assertThat(execution.finalizeResult(claim, WebhookHttpDeliveryResult.http(204, 10))).isTrue();
        assertThat(execution.finalizeResult(claim, WebhookHttpDeliveryResult.http(204, 10))).isFalse();
        assertThat(count("flowpay.webhook.delivered")).isEqualTo(1);
    }

    @Test
    void shouldCountExpiredLeaseRecoveryOnlyOnceAndAfterCommit() {
        var delivery = delivery("PENDING", -1);
        var claim = execution.claim(delivery.deliveryId(), delivery.endpointId()).orElseThrow();
        jdbc.update("UPDATE webhook_deliveries SET lease_expires_at=CURRENT_TIMESTAMP - INTERVAL '1 second'");
        doAnswer(invocation -> { var result = invocation.callRealMethod(); rollbackBeforeCommit(); return result; }).when(attempts).complete(any());
        assertThatThrownBy(() -> execution.recoverExpired(claim.deliveryId(), claim.endpointId(), claim.attemptNo())).isInstanceOf(RuntimeException.class);
        assertThat(count("flowpay.webhook.lease.recoveries")).isZero();
        doCallRealMethod().when(attempts).complete(any());
        assertThat(execution.recoverExpired(claim.deliveryId(), claim.endpointId(), claim.attemptNo())).isTrue();
        assertThat(execution.recoverExpired(claim.deliveryId(), claim.endpointId(), claim.attemptNo())).isFalse();
        assertThat(execution.finalizeResult(claim, WebhookHttpDeliveryResult.http(204, 10))).isFalse();
        assertThat(count("flowpay.webhook.lease.recoveries")).isEqualTo(1);
        assertThat(count("flowpay.webhook.delivered")).isZero();
    }

    @Test
    void shouldKeepHttpAttemptVisibleIfItsLateResultIsFencedOut() {
        var delivery = delivery("PENDING", -1);
        when(http.send(any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            jdbc.update("UPDATE webhook_deliveries SET lease_expires_at=CURRENT_TIMESTAMP - INTERVAL '1 second'");
            execution.recoverExpired(delivery.deliveryId(), delivery.endpointId(), 1);
            return WebhookHttpDeliveryResult.http(204, 10);
        });
        worker().deliverBatch();
        assertThat(count("flowpay.webhook.http.outcomes", "outcome", "HTTP_2XX")).isEqualTo(1);
        assertThat(count("flowpay.webhook.delivered")).isZero();
        assertThat(count("flowpay.webhook.lease.recoveries")).isEqualTo(1);
    }

    private WebhookDeliveryWorker worker() {
        return new WebhookDeliveryWorker(execution, http, cipher,
                new WebhookDeliveryWorkerProperties(true, Duration.ofSeconds(1), 100, Duration.ofSeconds(30)), Clock.systemUTC(), webhookMetrics);
    }

    private void outbox(String suffix, String status, int ageOffset) {
        jdbc.update("""
                INSERT INTO outbox_events(event_id,aggregate_type,aggregate_id,event_type,payload,status,occurred_at,
                    available_at,published_at,created_at)
                VALUES (?, 'PAYMENT_INTENT', 'pi_sensitive', 'payment.processing.v1',
                    '{"merchantId":1,"paymentPublicId":"pi_sensitive","amountMinor":100,"currency":"USD","occurredAt":"2026-10-04T00:00:00Z"}',
                    ?, '2026-10-04T00:00:00Z', CURRENT_TIMESTAMP - INTERVAL '1 second',
                    CASE WHEN ?='PUBLISHED' THEN CURRENT_TIMESTAMP ELSE NULL END,
                    CURRENT_TIMESTAMP + ? * INTERVAL '1 second')
                """, "ievt_" + suffix, status, status, ageOffset);
    }

    private ClaimedWebhookDelivery delivery(String status, int dueOffset) {
        long endpoint = jdbc.queryForObject("""
                INSERT INTO webhook_endpoints(public_id,merchant_id,url,secret_ciphertext,status,created_at,updated_at)
                VALUES ('wep_' || nextval('webhook_endpoints_id_seq'), ?, 'https://merchant.example/hook', ?, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP) RETURNING id
                """, Long.class, merchant, cipher.encrypt("whsec_synthetic"));
        jdbc.update("INSERT INTO webhook_endpoint_events(endpoint_id,event_type) VALUES (?, 'payment.succeeded')", endpoint);
        long event = jdbc.queryForObject("""
                INSERT INTO webhook_events(public_id,source_event_id,merchant_id,event_type,resource_type,resource_id,payload,occurred_at,created_at)
                VALUES ('evt_' || ?, 'source_' || ?, ?, 'payment.succeeded', 'PAYMENT_INTENT', 'pi_sensitive', '{}', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP) RETURNING id
                """, Long.class, endpoint, endpoint, merchant);
        long id = jdbc.queryForObject("""
                INSERT INTO webhook_deliveries(public_id,webhook_event_id,webhook_endpoint_id,status,next_attempt_at,lease_expires_at,delivered_at,created_at,updated_at)
                VALUES ('wdl_' || ?, ?, ?, ?,
                    CASE WHEN ? IN ('PENDING','RETRYING') THEN CURRENT_TIMESTAMP + ? * INTERVAL '1 second' ELSE NULL END,
                    CASE WHEN ?='DELIVERING' THEN CURRENT_TIMESTAMP + INTERVAL '30 seconds' ELSE NULL END,
                    CASE WHEN ?='DELIVERED' THEN CURRENT_TIMESTAMP ELSE NULL END, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP) RETURNING id
                """, Long.class, endpoint, event, endpoint, status, status, dueOffset, status, status);
        return new ClaimedWebhookDelivery(id, endpoint, 0, "evt_" + endpoint, "https://merchant.example/hook", "synthetic", "{}");
    }

    private PostPaymentSucceededCommand paymentCommand(long amount) {
        return new PostPaymentSucceededCommand(merchant, "pi_metrics_async", new Money(amount, Currency.getInstance("USD")), Instant.parse("2026-10-04T00:00:00Z"));
    }

    private double count(String name, String... tags) {
        var counter = registry.find(name).tags(tags).counter();
        return counter == null ? 0 : counter.count();
    }

    private static void rollbackBeforeCommit() {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void beforeCommit(boolean readOnly) { throw new IllegalStateException("synthetic rollback"); }
        });
    }

    private static void removeCounters(MeterRegistry registry) {
        if (registry instanceof CompositeMeterRegistry composite) composite.getRegistries().forEach(OperationalMetricsIntegrationTest::removeCounters);
        registry.getMeters().stream().filter(meter -> meter.getId().getName().startsWith("flowpay.") && meter.getId().getType() == Meter.Type.COUNTER)
                .toList().forEach(registry::remove);
    }
}
