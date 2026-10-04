package com.flowpay.backend.webhook.application;

import com.flowpay.backend.common.money.Money;
import com.flowpay.backend.testing.PostgresIntegrationTest;
import com.flowpay.backend.webhook.domain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static com.flowpay.backend.webhook.application.WebhookMaterializationResult.Outcome.*;

@SpringBootTest
@ActiveProfiles("test")
class WebhookEventMaterializationIntegrationTest extends PostgresIntegrationTest {
    private static final Instant OCCURRED = Instant.parse("2026-10-03T01:02:03.123456789Z");
    @Autowired private WebhookEventMaterializationService service;
    @Autowired private WebhookEndpointManagementService management;
    @Autowired private WebhookEndpointRepository endpoints;
    @Autowired private WebhookEventRepository events;
    @MockitoSpyBean private WebhookDeliveryRepository deliveries;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper mapper;
    @Autowired private PlatformTransactionManager transactions;
    private long merchant;

    @BeforeEach
    void cleanData() {
        jdbc.execute("TRUNCATE TABLE merchants, users RESTART IDENTITY CASCADE");
        merchant = jdbc.queryForObject("""
                INSERT INTO merchants (public_id, name, status, created_at, updated_at)
                VALUES ('mrc_materializer', 'Materializer', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                RETURNING id
                """, Long.class);
    }

    @Test
    void snapshotsOnlyActiveMatchingMerchantEndpointsAndInitialDeliveryState() {
        long first = endpoint("one", merchant, WebhookEventType.PAYMENT_SUCCEEDED);
        long second = endpoint("two", merchant, WebhookEventType.PAYMENT_SUCCEEDED);
        endpoint("unsubscribed", merchant, WebhookEventType.REFUND_SUCCEEDED);
        endpoint("disabled", merchant, WebhookEventType.PAYMENT_SUCCEEDED);
        management.disable("mrc_materializer", "wep_disabled");
        long other = jdbc.queryForObject("""
                INSERT INTO merchants (public_id, name, status, created_at, updated_at)
                VALUES ('mrc_other', 'Other', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP) RETURNING id
                """, Long.class);
        endpoint("other", other, WebhookEventType.PAYMENT_SUCCEEDED);
        var result = service.materialize(command());
        assertThat(result.outcome()).isEqualTo(MATERIALIZED);
        assertThat(result.newDeliveryCount()).isEqualTo(2);
        var event = events.findBySourceEventId("ievt_materializer").orElseThrow();
        assertThat(event.occurredAt()).isEqualTo(OCCURRED.truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        assertThat(mapper.readTree(event.payload()).path("createdAt").asString()).isEqualTo(OCCURRED.toString());
        assertThat(jdbc.queryForList("SELECT webhook_endpoint_id FROM webhook_deliveries", Long.class))
                .containsExactlyInAnyOrder(first, second);
        assertThat(jdbc.queryForList("""
                SELECT status || ':' || attempt_count || ':' || (next_attempt_at = created_at) || ':'
                    || (lease_expires_at IS NULL) || ':' || (delivered_at IS NULL) FROM webhook_deliveries
                """, String.class)).containsExactly("PENDING:0:true:true:true", "PENDING:0:true:true:true");
    }

    @Test
    void persistsWithNoSubscribersAndDuplicateDoesNotRecomputeLaterSubscriptions() {
        var original = service.materialize(command());
        String payload = events.findBySourceEventId("ievt_materializer").orElseThrow().payload();
        endpoint("late", merchant, WebhookEventType.PAYMENT_SUCCEEDED);
        assertThat(service.materialize(command()))
                .isEqualTo(new WebhookMaterializationResult(ALREADY_MATERIALIZED, original.eventPublicId(), 0));
        assertThat(count("webhook_events")).isOne();
        assertThat(count("webhook_deliveries")).isZero();
        assertThat(events.findBySourceEventId("ievt_materializer").orElseThrow().payload()).isEqualTo(payload);
    }

    @ParameterizedTest
    @ValueSource(strings = {"merchant", "type", "resource", "amount", "currency", "time"})
    void contradictoryDuplicatesFailClosedWithoutChangingSnapshot(String changed) {
        endpoint("one", merchant, WebhookEventType.PAYMENT_SUCCEEDED);
        service.materialize(command());
        var incoming = new MaterializeWebhookEventCommand("ievt_materializer",
                changed.equals("merchant") ? merchant + 1 : merchant,
                changed.equals("type") ? WebhookEventType.PAYMENT_PROCESSING : WebhookEventType.PAYMENT_SUCCEEDED,
                changed.equals("resource") ? "pi_other" : "pi_materializer", null,
                Money.of(changed.equals("amount") ? 9000 : 10000, changed.equals("currency") ? "USD" : "VND"),
                null, null, changed.equals("time") ? OCCURRED.plusNanos(1) : OCCURRED);
        assertThatThrownBy(() -> service.materialize(incoming)).isInstanceOf(ContradictoryWebhookSourceEventException.class);
        assertThat(count("webhook_events")).isOne();
        assertThat(count("webhook_deliveries")).isOne();
    }

    @Test
    void refundPaymentIdAndFailureMetadataArePartOfDedupeFacts() {
        var original = new MaterializeWebhookEventCommand("ievt_materializer", merchant, WebhookEventType.REFUND_FAILED,
                "re_materializer", "pi_materializer", Money.of(10000, "VND"), "DECLINED", "Refund declined", OCCURRED);
        service.materialize(original);
        for (var incoming : List.of(
                new MaterializeWebhookEventCommand(original.sourceEventId(), merchant, original.eventType(),
                        original.resourceId(), "pi_other", original.amount(), original.failureCode(), original.failureMessage(), OCCURRED),
                new MaterializeWebhookEventCommand(original.sourceEventId(), merchant, original.eventType(),
                        original.resourceId(), original.paymentId(), original.amount(), "OTHER", original.failureMessage(), OCCURRED),
                new MaterializeWebhookEventCommand(original.sourceEventId(), merchant, original.eventType(),
                        original.resourceId(), original.paymentId(), original.amount(), original.failureCode(), "Other failure", OCCURRED))) {
            assertThatThrownBy(() -> service.materialize(incoming)).isInstanceOf(ContradictoryWebhookSourceEventException.class);
        }
        assertThat(count("webhook_events")).isOne();
    }

    @Test
    void deliveryFailureRollsBackEventAndAllDeliveries() {
        endpoint("one", merchant, WebhookEventType.PAYMENT_SUCCEEDED);
        doThrow(new DataIntegrityViolationException("Simulated persistence failure")).when(deliveries).save(any());
        assertThatThrownBy(() -> service.materialize(command())).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(count("webhook_events")).isZero();
        assertThat(count("webhook_deliveries")).isZero();
    }

    @Test
    void concurrentSourceDuplicatesCreateExactlyOneEventAndSubscriptionSnapshot() throws Exception {
        long firstEndpoint = endpoint("one", merchant, WebhookEventType.PAYMENT_SUCCEEDED);
        long secondEndpoint = endpoint("two", merchant, WebhookEventType.PAYMENT_SUCCEEDED);
        endpoint("not_subscribed", merchant, WebhookEventType.REFUND_SUCCEEDED);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(6)) {
            List<Future<WebhookMaterializationResult>> calls = java.util.stream.IntStream.range(0, 6)
                    .mapToObj(i -> executor.submit(() -> { start.await(); return service.materialize(command()); })).toList();
            start.countDown();
            var results = new java.util.ArrayList<WebhookMaterializationResult>();
            for (var call : calls) results.add(call.get(15, TimeUnit.SECONDS));
            assertThat(results).filteredOn(r -> r.outcome() == MATERIALIZED).hasSize(1);
            assertThat(results).extracting(WebhookMaterializationResult::eventPublicId).containsOnly(results.getFirst().eventPublicId());
        }
        assertThat(count("webhook_events")).isOne();
        assertThat(count("webhook_deliveries")).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT webhook_endpoint_id FROM webhook_deliveries", Long.class))
                .containsExactlyInAnyOrder(firstEndpoint, secondEndpoint);
        assertThat(jdbc.queryForList("SELECT DISTINCT webhook_event_id FROM webhook_deliveries", Long.class))
                .hasSize(1);
    }

    @Test
    void subscriptionSnapshotSharedLockBlocksDisableUntilTransactionCommits() throws Exception {
        endpoint("one", merchant, WebhookEventType.PAYMENT_SUCCEEDED);
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var snapshot = executor.submit(() -> tx(() -> {
                assertThat(endpoints.findActiveSubscribedIdsForShare(merchant, WebhookEventType.PAYMENT_SUCCEEDED)).hasSize(1);
                locked.countDown();
                waitFor(release);
                return null;
            }));
            try {
                assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
                var disable = executor.submit(() -> management.disable("mrc_materializer", "wep_one"));
                await().atMost(java.time.Duration.ofSeconds(5)).untilAsserted(() -> assertThat(jdbc.queryForObject("""
                        SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'
                          AND query LIKE '%SELECT id FROM webhook_endpoints%'
                        """, Long.class)).isPositive());
                assertThat(disable.isDone()).isFalse();
                release.countDown();
                snapshot.get(5, TimeUnit.SECONDS);
                disable.get(5, TimeUnit.SECONDS);
            } finally { release.countDown(); }
        }
        assertThat(service.materialize(command()).newDeliveryCount()).isZero();
    }

    private long endpoint(String suffix, long owner, WebhookEventType type) {
        return tx(() -> endpoints.save(WebhookEndpoint.create("wep_" + suffix, owner,
                "https://example.com/webhooks", "encrypted", List.of(type), OCCURRED))).internalId();
    }

    private MaterializeWebhookEventCommand command() {
        return new MaterializeWebhookEventCommand("ievt_materializer", merchant, WebhookEventType.PAYMENT_SUCCEEDED,
                "pi_materializer", null, Money.of(10000, "VND"), null, null, OCCURRED);
    }

    private long count(String table) { return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class); }
    private <T> T tx(Supplier<T> work) { return new TransactionTemplate(transactions).execute(status -> work.get()); }
    private static void waitFor(CountDownLatch latch) {
        try { if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Latch timed out"); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
    }
}
