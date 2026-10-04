package com.flowpay.backend.webhook.application;

import com.flowpay.backend.testing.PostgresIntegrationTest;
import com.flowpay.backend.webhook.domain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = "flowpay.webhook.delivery-worker.batch-size=2")
@ActiveProfiles("test")
class WebhookDeliveryRecoveryIntegrationTest extends PostgresIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-04T00:00:00Z");
    private static final List<Duration> DELAYS = List.of(Duration.ofSeconds(10), Duration.ofSeconds(30),
            Duration.ofMinutes(2), Duration.ofMinutes(10), Duration.ofHours(1));
    @Autowired private WebhookDeliveryExecutionService execution;
    @Autowired private WebhookEndpointManagementService management;
    @Autowired private WebhookDeliveryCancellationService cancellation;
    @Autowired private WebhookEndpointRepository endpoints;
    @Autowired private WebhookEventRepository events;
    @MockitoSpyBean private WebhookDeliveryRepository deliveries;
    @MockitoSpyBean private WebhookDeliveryAttemptRepository attempts;
    @Autowired private WebhookSecretCipher cipher;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;
    @MockitoBean private WebhookHttpClientPort http;
    @MockitoBean private Clock clock;
    private final AtomicReference<Instant> now = new AtomicReference<>(NOW);
    private long merchantId;

    @BeforeEach
    void setUp() {
        now.set(NOW);
        when(clock.instant()).thenAnswer(call -> now.get());
        jdbc.execute("TRUNCATE TABLE merchants, users RESTART IDENTITY CASCADE");
        merchantId = jdbc.queryForObject("""
                INSERT INTO merchants (public_id, name, status, created_at, updated_at)
                VALUES ('mrc_recovery', 'Recovery', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP) RETURNING id
                """, Long.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {410, 429, 503})
    void sixFailuresUseAllFiveJitteredDelaysThenDeadIsNeverAutomaticallyClaimed(int httpStatus) {
        var delivery = delivery("budget", endpoint("budget"));
        when(http.send(any())).thenReturn(WebhookHttpDeliveryResult.http(httpStatus, 10));
        for (int no = 1; no <= 6; no++) {
            Instant failedAt = now.get();
            worker().deliverBatch();
            var current = load(delivery);
            assertThat(current.attemptCount()).isEqualTo(no);
            assertThat(current.leaseExpiresAt()).isNull();
            var history = attempts.findByDeliveryIdAndAttemptNo(delivery.internalId(), no).orElseThrow();
            assertThat(history.httpStatus()).isEqualTo(httpStatus);
            assertThat(history.errorMessage()).isEqualTo("HTTP_STATUS");
            assertThat(history.finishedAt()).isEqualTo(failedAt);
            if (no < 6) {
                Duration delay = DELAYS.get(no - 1);
                assertThat(current.status()).isEqualTo(WebhookDeliveryStatus.RETRYING);
                assertThat(current.nextAttemptAt()).isBetween(failedAt.plus(delay), failedAt.plusNanos(delay.toNanos() * 12 / 10));
                now.set(current.nextAttemptAt().minusMillis(1));
                worker().deliverBatch();
                assertThat(load(delivery).attemptCount()).isEqualTo(no);
                now.set(current.nextAttemptAt());
            } else {
                assertThat(current.status()).isEqualTo(WebhookDeliveryStatus.DEAD);
                assertThat(current.nextAttemptAt()).isNull();
            }
        }
        now.set(now.get().plus(Duration.ofDays(3)));
        worker().deliverBatch();
        assertThat(execution.candidates()).isEmpty();
        assertThat(execution.expiredCandidates()).isEmpty();
        assertThat(attempts.findAllByDeliveryId(delivery.internalId())).hasSize(6);
        assertThat(endpoints.findByPublicIdAndMerchantId("wep_budget", merchantId).orElseThrow().status())
                .isEqualTo(WebhookEndpointStatus.ACTIVE);
        verify(http, times(6)).send(any());
    }

    @Test
    void retrySuccessPreservesEventBodyAndCompletesRetainedAttemptHistory() {
        var delivery = delivery("retry_success", endpoint("retry_success"));
        when(http.send(any())).thenReturn(WebhookHttpDeliveryResult.http(503, 1), WebhookHttpDeliveryResult.http(204, 2));
        worker().deliverBatch();
        now.set(load(delivery).nextAttemptAt());
        worker().deliverBatch();
        assertThat(load(delivery).status()).isEqualTo(WebhookDeliveryStatus.DELIVERED);
        assertThat(load(delivery).attemptCount()).isEqualTo(2);
        assertThat(load(delivery).lastError()).isNull();
        assertThat(attempts.findAllByDeliveryId(delivery.internalId())).extracting(WebhookDeliveryAttempt::httpStatus)
                .containsExactly(503, 204);
        var requests = org.mockito.ArgumentCaptor.forClass(WebhookHttpDeliveryRequest.class);
        verify(http, times(2)).send(requests.capture());
        assertThat(requests.getAllValues().get(1).body()).isEqualTo(requests.getAllValues().getFirst().body());
        assertThat(requests.getAllValues().get(1).eventPublicId()).isEqualTo(requests.getAllValues().getFirst().eventPublicId());
    }

    @Test
    void expiresExactlyAtLeaseDeadlineAndFencesLateWorkerBeforeAndAfterNextClaim() {
        var delivery = delivery("expired", endpoint("expired"));
        var old = claim(delivery);
        now.set(NOW.plusSeconds(30).minusMillis(1));
        assertThat(recover(old)).isFalse();
        assertThat(execution.expiredCandidates()).isEmpty();
        now.set(NOW.plusSeconds(30));
        assertThat(execution.expiredCandidates()).extracting(WebhookDelivery::internalId).containsExactly(delivery.internalId());
        assertThat(recover(old)).isTrue();
        assertThat(recover(old)).isFalse();
        assertThat(load(delivery).status()).isEqualTo(WebhookDeliveryStatus.RETRYING);
        assertThat(load(delivery).attemptCount()).isOne();
        assertThat(load(delivery).nextAttemptAt()).isBetween(now.get().plusSeconds(10), now.get().plusSeconds(12));
        var abandoned = attempts.findByDeliveryIdAndAttemptNo(delivery.internalId(), 1).orElseThrow();
        assertThat(abandoned.finishedAt()).isEqualTo(now.get());
        assertThat(abandoned.errorMessage()).isEqualTo("DELIVERY_LEASE_EXPIRED");
        assertThat(abandoned.httpStatus()).isNull();
        assertThat(abandoned.durationMs()).isNull();
        assertThat(execution.finalizeResult(old, WebhookHttpDeliveryResult.http(200, 1))).isFalse();
        now.set(load(delivery).nextAttemptAt());
        var current = claim(delivery);
        assertThat(current.attemptNo()).isEqualTo(2);
        assertThat(recover(old)).isFalse();
        assertThat(execution.finalizeResult(old, WebhookHttpDeliveryResult.http(200, 1))).isFalse();
        assertThat(execution.finalizeResult(current, WebhookHttpDeliveryResult.http(200, 1))).isTrue();
        assertThat(attempts.findByDeliveryIdAndAttemptNo(delivery.internalId(), 1).orElseThrow().errorMessage())
                .isEqualTo("DELIVERY_LEASE_EXPIRED");
        verifyNoInteractions(http);
    }

    @Test
    void expiredSixthAttemptExhaustsBudgetWithoutInventingAnotherAttempt() {
        var delivery = delivery("expired_budget", endpoint("expired_budget"));
        for (int no = 1; no <= 5; no++) {
            execution.finalizeResult(claim(delivery), WebhookHttpDeliveryResult.http(500, 1));
            now.set(load(delivery).nextAttemptAt());
        }
        var last = claim(delivery);
        now.set(load(delivery).leaseExpiresAt());
        assertThat(recover(last)).isTrue();
        assertThat(load(delivery).status()).isEqualTo(WebhookDeliveryStatus.DEAD);
        assertThat(load(delivery).attemptCount()).isEqualTo(6);
        assertThat(load(delivery).lastError()).isEqualTo("DELIVERY_LEASE_EXPIRED");
        assertThat(attempts.findAllByDeliveryId(delivery.internalId())).hasSize(6);
        assertThat(execution.candidates()).isEmpty();
        verifyNoInteractions(http);
    }

    @Test
    void recoveryIncludesDisabledEndpointsAndMarksTheirAbandonedDeliveryDead() {
        var delivery = delivery("disabled_expired", endpoint("disabled_expired"));
        var claim = claim(delivery);
        disable("disabled_expired");
        assertThat(load(delivery).status()).isEqualTo(WebhookDeliveryStatus.DELIVERING);
        now.set(NOW.plusSeconds(30));
        worker().deliverBatch();
        assertThat(load(delivery).status()).isEqualTo(WebhookDeliveryStatus.DEAD);
        assertThat(load(delivery).lastError()).isEqualTo("DELIVERY_LEASE_EXPIRED");
        assertThat(load(delivery).nextAttemptAt()).isNull();
        assertThat(execution.finalizeResult(claim, WebhookHttpDeliveryResult.http(200, 1))).isFalse();
        verifyNoInteractions(http);
    }

    @Test
    void concurrentRecoveryHasExactlyOneWinnerAndOneImmutableCompletion() throws Exception {
        var delivery = delivery("recovery_race", endpoint("recovery_race"));
        var claim = claim(delivery);
        now.set(NOW.plusSeconds(30));
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> { waitFor(start); return recover(claim); });
            var b = pool.submit(() -> { waitFor(start); return recover(claim); });
            start.countDown();
            assertThat(List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS))).containsExactlyInAnyOrder(true, false);
        }
        assertThat(attempts.findAllByDeliveryId(delivery.internalId())).hasSize(1);
        assertThat(load(delivery).status()).isEqualTo(WebhookDeliveryStatus.RETRYING);
        verifyNoInteractions(http);
    }

    @Test
    void recoveryAndLateFinalizationSerializeWithoutContradictoryHistory() throws Exception {
        var delivery = delivery("result_race", endpoint("result_race"));
        var claim = claim(delivery);
        now.set(NOW.plusSeconds(30));
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var recovery = pool.submit(() -> { waitFor(start); return recover(claim); });
            var result = pool.submit(() -> { waitFor(start); return execution.finalizeResult(claim, WebhookHttpDeliveryResult.http(200, 1)); });
            start.countDown();
            assertThat(List.of(recovery.get(10, TimeUnit.SECONDS), result.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        var current = load(delivery);
        var history = attempts.findByDeliveryIdAndAttemptNo(delivery.internalId(), 1).orElseThrow();
        if (current.status() == WebhookDeliveryStatus.DELIVERED) {
            assertThat(history.httpStatus()).isEqualTo(200);
            assertThat(history.errorMessage()).isNull();
        } else {
            assertThat(current.status()).isEqualTo(WebhookDeliveryStatus.RETRYING);
            assertThat(history.errorMessage()).isEqualTo("DELIVERY_LEASE_EXPIRED");
            assertThat(history.httpStatus()).isNull();
        }
    }

    @Test
    void recoverySkipsContendedDeliveryAndEndpointWhileAnotherExpiredLeaseProgresses() throws Exception {
        var rowLocked = delivery("row_locked", endpoint("row_locked"));
        var endpointLocked = delivery("endpoint_locked", endpoint("endpoint_locked"));
        var free = delivery("free", endpoint("free"));
        claim(rowLocked);
        claim(endpointLocked);
        var freeClaim = claim(free);
        now.set(NOW.plusSeconds(30));
        var ready = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var held = pool.submit(() -> tx(() -> {
                deliveries.findByInternalIdForUpdate(rowLocked.internalId()).orElseThrow();
                endpoints.findByPublicIdAndMerchantIdForUpdate("wep_endpoint_locked", merchantId).orElseThrow();
                ready.countDown();
                waitFor(release);
                return null;
            }));
            try {
                assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(execution.expiredCandidates()).extracting(WebhookDelivery::internalId).containsExactly(free.internalId());
                assertThat(execution.recoverExpired(rowLocked.internalId(), rowLocked.webhookEndpointId(), 1)).isFalse();
                assertThat(execution.recoverExpired(endpointLocked.internalId(), endpointLocked.webhookEndpointId(), 1)).isFalse();
                assertThat(recover(freeClaim)).isTrue();
            } finally { release.countDown(); }
            held.get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void recoveryRollbackLeavesOpenHistoryAndDoesNotPreventUnrelatedWork() {
        var broken = delivery("broken", endpoint("broken"));
        var good = delivery("good", endpoint("good"));
        var scheduled = delivery("scheduled", endpoint("scheduled"));
        claim(broken);
        claim(good);
        now.set(NOW.plusSeconds(30));
        doThrow(new DataAccessResourceFailureException("simulated"))
                .when(attempts).complete(argThat(a -> a.deliveryId() == broken.internalId()));
        when(http.send(any())).thenReturn(WebhookHttpDeliveryResult.http(200, 1));
        worker().deliverBatch();
        assertThat(load(broken).status()).isEqualTo(WebhookDeliveryStatus.DELIVERING);
        assertThat(attempts.findByDeliveryIdAndAttemptNo(broken.internalId(), 1).orElseThrow().finishedAt()).isNull();
        assertThat(load(good).status()).isEqualTo(WebhookDeliveryStatus.RETRYING);
        assertThat(load(scheduled).status()).isEqualTo(WebhookDeliveryStatus.DELIVERED);
        verify(http).send(any());
    }

    @Test
    void recoveryDeliveryWriteFailureRollsBackAbandonedAttemptCompletion() {
        var delivery = delivery("recovery_rollback", endpoint("recovery_rollback"));
        var claim = claim(delivery);
        now.set(NOW.plusSeconds(30));
        doThrow(new DataAccessResourceFailureException("simulated"))
                .when(deliveries).save(argThat(d -> d.status() == WebhookDeliveryStatus.RETRYING));
        assertThatThrownBy(() -> recover(claim)).isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(load(delivery).status()).isEqualTo(WebhookDeliveryStatus.DELIVERING);
        assertThat(load(delivery).leaseExpiresAt()).isEqualTo(NOW.plusSeconds(30));
        assertThat(attempts.findByDeliveryIdAndAttemptNo(delivery.internalId(), 1).orElseThrow().finishedAt()).isNull();
    }

    @Test
    void recoversBoundedBatchWithoutImmediateHttpRetry() {
        var first = delivery("batch_first", endpoint("batch_first"));
        var second = delivery("batch_second", endpoint("batch_second"));
        var third = delivery("batch_third", endpoint("batch_third"));
        claim(first);
        claim(second);
        claim(third);
        now.set(NOW.plusSeconds(30));
        assertThat(execution.expiredCandidates()).extracting(WebhookDelivery::internalId)
                .containsExactly(first.internalId(), second.internalId());
        worker().deliverBatch();
        assertThat(load(first).status()).isEqualTo(WebhookDeliveryStatus.RETRYING);
        assertThat(load(second).status()).isEqualTo(WebhookDeliveryStatus.RETRYING);
        assertThat(load(third).status()).isEqualTo(WebhookDeliveryStatus.DELIVERING);
        worker().deliverBatch();
        assertThat(load(third).status()).isEqualTo(WebhookDeliveryStatus.RETRYING);
        verifyNoInteractions(http);
    }

    @Test
    void disableCancelsAllPagesOfScheduledWorkAndKeepsInflightTerminalAndOtherEndpointUnchanged() {
        long endpoint = endpoint("cancel");
        var pending = java.util.stream.IntStream.range(0, 5).mapToObj(i -> delivery("pending" + i, endpoint)).toList();
        var retrying = delivery("retrying", endpoint);
        execution.finalizeResult(claim(retrying), WebhookHttpDeliveryResult.http(503, 1));
        var inFlightSuccess = delivery("inflight_success", endpoint);
        var successClaim = claim(inFlightSuccess);
        var inFlightFailure = delivery("inflight_failure", endpoint);
        var failureClaim = claim(inFlightFailure);
        var delivered = delivery("delivered", endpoint);
        execution.finalizeResult(claim(delivered), WebhookHttpDeliveryResult.http(204, 1));
        var other = delivery("other", endpoint("other"));
        disable("cancel");
        for (var delivery : pending) {
            assertThat(load(delivery).status()).isEqualTo(WebhookDeliveryStatus.DEAD);
            assertThat(load(delivery).attemptCount()).isZero();
            assertThat(load(delivery).nextAttemptAt()).isNull();
            assertThat(load(delivery).lastError()).isEqualTo("ENDPOINT_DISABLED");
            assertThat(attempts.findAllByDeliveryId(delivery.internalId())).isEmpty();
        }
        assertThat(load(retrying).status()).isEqualTo(WebhookDeliveryStatus.DEAD);
        assertThat(load(retrying).attemptCount()).isOne();
        assertThat(load(retrying).lastHttpStatus()).isEqualTo(503);
        assertThat(attempts.findAllByDeliveryId(retrying.internalId())).hasSize(1);
        assertThat(load(inFlightSuccess).status()).isEqualTo(WebhookDeliveryStatus.DELIVERING);
        assertThat(load(delivered).status()).isEqualTo(WebhookDeliveryStatus.DELIVERED);
        assertThat(load(other).status()).isEqualTo(WebhookDeliveryStatus.PENDING);
        assertThat(execution.claim(pending.getFirst().internalId(), endpoint)).isEmpty();
        assertThat(execution.finalizeResult(successClaim, WebhookHttpDeliveryResult.http(200, 1))).isTrue();
        assertThat(execution.finalizeResult(failureClaim, WebhookHttpDeliveryResult.http(500, 1))).isTrue();
        assertThat(load(inFlightSuccess).status()).isEqualTo(WebhookDeliveryStatus.DELIVERED);
        assertThat(load(inFlightFailure).status()).isEqualTo(WebhookDeliveryStatus.DEAD);
        long version = load(retrying).version();
        disable("cancel");
        assertThat(load(retrying).version()).isEqualTo(version);
        verifyNoInteractions(http);
    }

    @Test
    void cancellationFailureOnLaterPageRollsBackEndpointAndEarlierDeliveryChanges() {
        long endpoint = endpoint("cancel_rollback");
        var pending = java.util.stream.IntStream.range(0, 5).mapToObj(i -> delivery("rollback" + i, endpoint)).toList();
        doThrow(new DataAccessResourceFailureException("simulated"))
                .when(deliveries).save(argThat(d -> d.publicId().equals("wdl_rollback2") && d.status() == WebhookDeliveryStatus.DEAD));
        assertThatThrownBy(() -> disable("cancel_rollback")).isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(endpoints.findByPublicIdAndMerchantId("wep_cancel_rollback", merchantId).orElseThrow().status())
                .isEqualTo(WebhookEndpointStatus.ACTIVE);
        for (var delivery : pending) {
            assertThat(load(delivery).status()).isEqualTo(WebhookDeliveryStatus.PENDING);
            assertThat(load(delivery).version()).isZero();
        }
    }

    @Test
    void disableWaitsForClaimCommitThenLeavesOnlyThatRequestInflight() throws Exception {
        long endpoint = endpoint("claim_disable");
        var inFlight = delivery("claimed", endpoint);
        var scheduled = delivery("not_claimed", endpoint);
        var opened = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(call -> {
            opened.countDown();
            waitFor(release);
            return call.callRealMethod();
        }).when(attempts).insert(any());
        try (var pool = Executors.newFixedThreadPool(2)) {
            var claiming = pool.submit(() -> claim(inFlight));
            try {
                assertThat(opened.await(5, TimeUnit.SECONDS)).isTrue();
                var disabling = pool.submit(() -> disable("claim_disable"));
                await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(jdbc.queryForObject("""
                        SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'
                            AND query LIKE '%SELECT id FROM webhook_endpoints%'
                        """, Long.class)).isPositive());
                assertThat(disabling.isDone()).isFalse();
                release.countDown();
                var claim = claiming.get(10, TimeUnit.SECONDS);
                disabling.get(10, TimeUnit.SECONDS);
                assertThat(load(inFlight).status()).isEqualTo(WebhookDeliveryStatus.DELIVERING);
                assertThat(load(scheduled).status()).isEqualTo(WebhookDeliveryStatus.DEAD);
                assertThat(execution.finalizeResult(claim, WebhookHttpDeliveryResult.http(200, 1))).isTrue();
            } finally { release.countDown(); }
        }
    }

    @Test
    void disableRacingRecoveryUltimatelyStopsRetryWithoutRewritingAbandonedHistory() throws Exception {
        var delivery = delivery("disable_recovery", endpoint("disable_recovery"));
        var claim = claim(delivery);
        now.set(NOW.plusSeconds(30));
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var recovery = pool.submit(() -> { waitFor(start); return recover(claim); });
            var disabling = pool.submit(() -> { waitFor(start); disable("disable_recovery"); });
            start.countDown();
            boolean recovered = recovery.get(10, TimeUnit.SECONDS);
            disabling.get(10, TimeUnit.SECONDS);
            if (!recovered) assertThat(recover(claim)).isTrue();
        }
        assertThat(load(delivery).status()).isEqualTo(WebhookDeliveryStatus.DEAD);
        assertThat(load(delivery).nextAttemptAt()).isNull();
        assertThat(attempts.findAllByDeliveryId(delivery.internalId())).hasSize(1);
        assertThat(attempts.findByDeliveryIdAndAttemptNo(delivery.internalId(), 1).orElseThrow().errorMessage())
                .isEqualTo("DELIVERY_LEASE_EXPIRED");
        assertThat(execution.finalizeResult(claim, WebhookHttpDeliveryResult.http(200, 1))).isFalse();
    }

    @Test
    void cancellationCannotRunInAnIndependentTransactionAccidentally() {
        long endpoint = endpoint("mandatory");
        assertThatThrownBy(() -> cancellation.cancelScheduled(endpoint)).isInstanceOf(IllegalTransactionStateException.class);
        verify(deliveries, never()).findScheduledByEndpointForUpdate(anyLong(), anyInt());
    }

    private long endpoint(String suffix) {
        return tx(() -> endpoints.save(WebhookEndpoint.create("wep_" + suffix, merchantId,
                "https://merchant.example/webhook", cipher.encrypt("whsec_test"),
                Set.of(WebhookEventType.PAYMENT_SUCCEEDED), NOW)).internalId());
    }

    private WebhookDelivery delivery(String suffix, long endpointId) {
        return tx(() -> {
            var event = events.tryInsert(WebhookEvent.create("evt_" + suffix, "source_" + suffix, merchantId,
                    WebhookEventType.PAYMENT_SUCCEEDED, WebhookResourceType.PAYMENT_INTENT, "pi_" + suffix,
                    "{\"id\":\"evt_" + suffix + "\"}", now.get(), now.get())).orElseThrow();
            return deliveries.save(WebhookDelivery.create("wdl_" + suffix, event.internalId(), endpointId, now.get()));
        });
    }

    private void disable(String suffix) { management.disable("mrc_recovery", "wep_" + suffix); }
    private WebhookDelivery load(WebhookDelivery delivery) { return deliveries.findByInternalId(delivery.internalId()).orElseThrow(); }
    private ClaimedWebhookDelivery claim(WebhookDelivery delivery) {
        return execution.claim(delivery.internalId(), delivery.webhookEndpointId()).orElseThrow();
    }
    private boolean recover(ClaimedWebhookDelivery claim) {
        return execution.recoverExpired(claim.deliveryId(), claim.endpointId(), claim.attemptNo());
    }
    private WebhookDeliveryWorker worker() {
        return new WebhookDeliveryWorker(execution, http, cipher,
                new WebhookDeliveryWorkerProperties(true, Duration.ofSeconds(1), 2, Duration.ofSeconds(30)), clock);
    }
    private <T> T tx(Supplier<T> work) { return new TransactionTemplate(transactions).execute(status -> work.get()); }
    private static void waitFor(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test wait timed out");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("test interrupted");
        }
    }
}
