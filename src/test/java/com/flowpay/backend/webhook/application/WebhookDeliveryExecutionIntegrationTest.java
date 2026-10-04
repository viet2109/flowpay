package com.flowpay.backend.webhook.application;

import com.flowpay.backend.testing.PostgresIntegrationTest;
import com.flowpay.backend.webhook.domain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = "flowpay.webhook.delivery-worker.batch-size=2")
@ActiveProfiles("test")
class WebhookDeliveryExecutionIntegrationTest extends PostgresIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-04T00:00:00Z");
    @Autowired private WebhookDeliveryExecutionService execution;
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
                VALUES ('mrc_worker', 'Worker', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP) RETURNING id
                """, Long.class);
    }

    @Test
    void claimsDuePendingAndRetryingButNotFutureOrDisabledAndHonorsBatchLimit() {
        var pending = delivery("pending", NOW.minusSeconds(60));
        var retrying = delivery("retry", NOW.minusSeconds(60));
        tx(() -> {
            var d = deliveries.findByInternalIdForUpdate(retrying.internalId()).orElseThrow();
            int no = d.claim(NOW.minusSeconds(60), NOW.minusSeconds(30));
            d.scheduleRetry(no, 503, "HTTP_STATUS", NOW.minusSeconds(1), NOW.minusSeconds(50));
            deliveries.save(d);
            return null;
        });
        var future = delivery("future", NOW.plusSeconds(1));
        var disabled = delivery("disabled", NOW.minusSeconds(120));
        disable(disabled.webhookEndpointId());
        var excess = delivery("excess", NOW);
        assertThat(execution.candidates()).extracting(WebhookDelivery::internalId)
                .containsExactly(pending.internalId(), retrying.internalId());
        assertThat(execution.claim(future.internalId(), future.webhookEndpointId())).isEmpty();
        assertThat(execution.claim(disabled.internalId(), disabled.webhookEndpointId())).isEmpty();
        var first = claim(pending);
        var second = claim(retrying);
        assertThat(first.attemptNo()).isOne();
        assertThat(second.attemptNo()).isEqualTo(2);
        assertThat(execution.claim(first.deliveryId(), first.endpointId())).isEmpty();
        assertThat(execution.candidates()).extracting(WebhookDelivery::internalId).containsExactly(excess.internalId());
        assertThat(attempts.findByDeliveryIdAndAttemptNo(first.deliveryId(), 1).orElseThrow().finishedAt()).isNull();
        assertThat(load(pending).leaseExpiresAt()).isEqualTo(NOW.plusSeconds(30));
        assertThat(load(pending).nextAttemptAt()).isNull();
        verifyNoInteractions(http);
    }

    @Test
    void competingWorkersCreateOneCommittedClaimAndOneOpenAttempt() throws Exception {
        var delivery = delivery("race", NOW);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> { start.await(); return execution.claim(delivery.internalId(), delivery.webhookEndpointId()); });
            var b = pool.submit(() -> { start.await(); return execution.claim(delivery.internalId(), delivery.webhookEndpointId()); });
            start.countDown();
            assertThat(java.util.List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS)))
                    .filteredOn(java.util.Optional::isPresent).hasSize(1);
        }
        assertThat(load(delivery).attemptCount()).isOne();
        assertThat(attempts.findAllByDeliveryId(delivery.internalId())).hasSize(1);
    }

    @Test
    void skipsContendedDeliveryAndEndpointSoUnrelatedDueRowsProgress() throws Exception {
        var lockedDelivery = delivery("locked", NOW);
        var lockedEndpoint = delivery("endpoint_locked", NOW);
        var free = delivery("free", NOW);
        var ready = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var held = pool.submit(() -> tx(() -> {
                deliveries.findByInternalIdForUpdate(lockedDelivery.internalId()).orElseThrow();
                endpoints.findByPublicIdAndMerchantIdForUpdate("wep_endpoint_locked", merchantId).orElseThrow();
                ready.countDown();
                waitFor(release);
                return null;
            }));
            try {
                assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(execution.candidates()).extracting(WebhookDelivery::internalId).containsExactly(free.internalId());
                assertThat(execution.claim(lockedDelivery.internalId(), lockedDelivery.webhookEndpointId())).isEmpty();
                assertThat(execution.claim(lockedEndpoint.internalId(), lockedEndpoint.webhookEndpointId())).isEmpty();
                assertThat(claim(free)).isNotNull();
            } finally { release.countDown(); }
            held.get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void sendsExactSnapshotAfterCommitAndClaimsEachDeliveryJustBeforeItsSend() {
        var first = delivery("first", NOW);
        var second = delivery("second", NOW);
        var beyondBatch = delivery("third", NOW);
        var original = events.findByInternalId(first.webhookEventId()).orElseThrow();
        when(http.send(any())).thenAnswer(call -> {
            WebhookHttpDeliveryRequest request = call.getArgument(0);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            if (request.eventPublicId().equals(original.publicId())) {
                assertThat(request.body()).isEqualTo(original.payload().getBytes(StandardCharsets.UTF_8));
                assertThat(request.secret()).isEqualTo("whsec_test");
                assertThat(load(first).status()).isEqualTo(WebhookDeliveryStatus.DELIVERING);
                assertThat(attempts.findByDeliveryIdAndAttemptNo(first.internalId(), 1).orElseThrow().finishedAt()).isNull();
                assertThat(load(second).status()).isEqualTo(WebhookDeliveryStatus.PENDING);
                now.set(NOW.plusSeconds(40));
            } else {
                assertThat(load(second).leaseExpiresAt()).isEqualTo(NOW.plusSeconds(70));
            }
            return WebhookHttpDeliveryResult.http(204, 12);
        });
        worker().deliverBatch();
        assertThat(load(first).status()).isEqualTo(WebhookDeliveryStatus.DELIVERED);
        assertThat(load(second).status()).isEqualTo(WebhookDeliveryStatus.DELIVERED);
        assertThat(load(beyondBatch).status()).isEqualTo(WebhookDeliveryStatus.PENDING);
        assertThat(attempts.findByDeliveryIdAndAttemptNo(first.internalId(), 1).orElseThrow().httpStatus()).isEqualTo(204);
        verify(http, times(2)).send(any());
    }

    @Test
    void failureCompletesAttemptAndDelegatesRetryWhileOtherDeliverySucceeds() {
        var failed = delivery("failure", NOW);
        var succeeded = delivery("success", NOW);
        when(http.send(any())).thenReturn(WebhookHttpDeliveryResult.http(503, 15), WebhookHttpDeliveryResult.http(200, 2));
        worker().deliverBatch();
        assertThat(load(failed).status()).isEqualTo(WebhookDeliveryStatus.RETRYING);
        assertThat(load(failed).nextAttemptAt()).isBetween(NOW.plusSeconds(10), NOW.plusSeconds(12));
        var history = attempts.findByDeliveryIdAndAttemptNo(failed.internalId(), 1).orElseThrow();
        assertThat(history.finishedAt()).isEqualTo(NOW);
        assertThat(history.errorMessage()).isEqualTo("HTTP_STATUS");
        assertThat(history.httpStatus()).isEqualTo(503);
        assertThat(load(succeeded).status()).isEqualTo(WebhookDeliveryStatus.DELIVERED);
    }

    @Test
    void unexpectedAdapterExceptionIsSanitizedAndDoesNotStopUnrelatedDelivery() {
        var failed = delivery("unexpected", NOW);
        var other = delivery("unrelated", NOW);
        when(http.send(any())).thenThrow(new IllegalStateException("whsec_secret https://sensitive.example"))
                .thenReturn(WebhookHttpDeliveryResult.http(200, 1));
        worker().deliverBatch();
        assertThat(load(failed).lastError()).isEqualTo("TRANSPORT_FAILURE");
        assertThat(attempts.findByDeliveryIdAndAttemptNo(failed.internalId(), 1).orElseThrow().errorMessage())
                .isEqualTo("TRANSPORT_FAILURE");
        assertThat(load(other).status()).isEqualTo(WebhookDeliveryStatus.DELIVERED);
    }

    @Test
    void snapshotSurvivesDisableAndInFlightSuccessMayFinishButFailureCannotRetry() {
        var success = delivery("disable_success", NOW);
        var failure = delivery("disable_failure", NOW);
        var a = claim(success);
        var b = claim(failure);
        disable(a.endpointId());
        disable(b.endpointId());
        assertThat(execution.finalizeResult(a, WebhookHttpDeliveryResult.http(200, 1))).isTrue();
        assertThat(execution.finalizeResult(b, WebhookHttpDeliveryResult.http(500, 1))).isTrue();
        assertThat(load(success).status()).isEqualTo(WebhookDeliveryStatus.DELIVERED);
        assertThat(load(failure).status()).isEqualTo(WebhookDeliveryStatus.DEAD);
        assertThat(load(failure).nextAttemptAt()).isNull();
        assertThat(a.toString()).doesNotContain(a.url(), a.payload(), a.secretCiphertext());
    }

    @Test
    void urlAndSecretRotationAffectNextAttemptOnly() {
        var delivery = delivery("rotated", NOW);
        var first = claim(delivery);
        tx(() -> {
            var endpoint = endpoints.findByPublicIdAndMerchantIdForUpdate("wep_rotated", merchantId).orElseThrow();
            endpoint.changeUrl("https://new-merchant.example/webhook", NOW);
            endpoint.rotateSecret(cipher.encrypt("whsec_rotated"), NOW);
            endpoints.save(endpoint);
            return null;
        });
        assertThat(first.url()).isEqualTo("https://merchant.example/webhook");
        assertThat(cipher.decrypt(first.secretCiphertext())).isEqualTo("whsec_test");
        execution.finalizeResult(first, WebhookHttpDeliveryResult.http(503, 1));
        now.set(NOW.plusSeconds(20));
        var second = claim(delivery);
        assertThat(second.url()).isEqualTo("https://new-merchant.example/webhook");
        assertThat(cipher.decrypt(second.secretCiphertext())).isEqualTo("whsec_rotated");
        assertThat(second.payload()).isEqualTo(first.payload());
        assertThat(second.eventPublicId()).isEqualTo(first.eventPublicId());
    }

    @Test
    void simultaneousFinalizationsHaveOneWinner() throws Exception {
        var delivery = delivery("finalize_race", NOW);
        var claim = claim(delivery);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> { start.await(); return execution.finalizeResult(claim, WebhookHttpDeliveryResult.http(200, 1)); });
            var b = pool.submit(() -> { start.await(); return execution.finalizeResult(claim, WebhookHttpDeliveryResult.http(503, 2)); });
            start.countDown();
            assertThat(java.util.List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        var history = attempts.findByDeliveryIdAndAttemptNo(delivery.internalId(), 1).orElseThrow();
        assertThat(history.httpStatus()).isEqualTo(load(delivery).lastHttpStatus());
        assertThat(load(delivery).status()).isIn(WebhookDeliveryStatus.DELIVERED, WebhookDeliveryStatus.RETRYING);
    }

    @Test
    void staleAndRepeatedResultsCannotOverwriteNewerAttemptOrHistoricalResult() {
        var delivery = delivery("fenced", NOW);
        var old = claim(delivery);
        assertThat(execution.finalizeResult(old, WebhookHttpDeliveryResult.http(503, 1))).isTrue();
        now.set(NOW.plusSeconds(20));
        var current = claim(delivery);
        assertThat(current.attemptNo()).isEqualTo(2);
        assertThat(execution.finalizeResult(old, WebhookHttpDeliveryResult.http(200, 1))).isFalse();
        assertThat(load(delivery).status()).isEqualTo(WebhookDeliveryStatus.DELIVERING);
        assertThat(execution.finalizeResult(current, WebhookHttpDeliveryResult.http(200, Long.MAX_VALUE))).isTrue();
        assertThat(execution.finalizeResult(current, WebhookHttpDeliveryResult.http(500, 1))).isFalse();
        assertThat(attempts.findByDeliveryIdAndAttemptNo(delivery.internalId(), 1).orElseThrow().httpStatus()).isEqualTo(503);
        assertThat(attempts.findByDeliveryIdAndAttemptNo(delivery.internalId(), 2).orElseThrow().durationMs())
                .isEqualTo(Integer.MAX_VALUE);
        assertThat(load(delivery).lastHttpStatus()).isEqualTo(200);
    }

    @Test
    void claimFailureRollsBackDeliveryAndDoesNotSend() {
        var delivery = delivery("claim_rollback", NOW);
        doThrow(new IllegalStateException("simulated")).when(attempts).insert(any());
        worker().deliverBatch();
        assertThat(load(delivery).status()).isEqualTo(WebhookDeliveryStatus.PENDING);
        assertThat(load(delivery).attemptCount()).isZero();
        assertThat(attempts.findAllByDeliveryId(delivery.internalId())).isEmpty();
        verifyNoInteractions(http);
    }

    @Test
    void finalizeFailureRollsBackAttemptAndLeavesRecoverableLeaseWithoutInlineResend() {
        var delivery = delivery("finalize_rollback", NOW);
        var claim = claim(delivery);
        doThrow(new DataAccessResourceFailureException("simulated")).when(deliveries).save(any());
        assertThatThrownBy(() -> execution.finalizeResult(claim, WebhookHttpDeliveryResult.http(200, 1)))
                .isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(load(delivery).status()).isEqualTo(WebhookDeliveryStatus.DELIVERING);
        assertThat(attempts.findByDeliveryIdAndAttemptNo(delivery.internalId(), 1).orElseThrow().finishedAt()).isNull();
    }

    @Test
    void disabledWorkerDoesNotClaimOrSendAndAmbientTransactionIsRejected() {
        var delivery = delivery("disabled_worker", NOW);
        new WebhookDeliveryWorker(execution, http, cipher,
                new WebhookDeliveryWorkerProperties(false, Duration.ofSeconds(1), 2, Duration.ofSeconds(30)), clock).deliverBatch();
        assertThat(load(delivery).status()).isEqualTo(WebhookDeliveryStatus.PENDING);
        assertThatThrownBy(() -> tx(() -> { worker().deliverBatch(); return null; }))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(http);
    }

    private WebhookDeliveryWorker worker() {
        return new WebhookDeliveryWorker(execution, http, cipher,
                new WebhookDeliveryWorkerProperties(true, Duration.ofSeconds(1), 2, Duration.ofSeconds(30)), clock);
    }

    private WebhookDelivery delivery(String suffix, Instant createdAt) {
        return tx(() -> {
            var endpoint = endpoints.save(WebhookEndpoint.create("wep_" + suffix, merchantId,
                    "https://merchant.example/webhook", cipher.encrypt("whsec_test"),
                    Set.of(WebhookEventType.PAYMENT_SUCCEEDED), NOW.minusSeconds(200)));
            var event = events.tryInsert(WebhookEvent.create("evt_" + suffix, "source_" + suffix, merchantId,
                    WebhookEventType.PAYMENT_SUCCEEDED, WebhookResourceType.PAYMENT_INTENT, "pi_" + suffix,
                    "{\"id\":\"evt_" + suffix + "\",\"data\":{\"note\":\"Tiếng Việt\"}}", createdAt, createdAt)).orElseThrow();
            return deliveries.save(WebhookDelivery.create("wdl_" + suffix, event.internalId(), endpoint.internalId(), createdAt));
        });
    }

    private ClaimedWebhookDelivery claim(WebhookDelivery delivery) {
        return execution.claim(delivery.internalId(), delivery.webhookEndpointId()).orElseThrow();
    }

    private WebhookDelivery load(WebhookDelivery delivery) { return deliveries.findByInternalId(delivery.internalId()).orElseThrow(); }

    private void disable(long endpointId) {
        String publicId = jdbc.queryForObject("SELECT public_id FROM webhook_endpoints WHERE id = ?", String.class, endpointId);
        tx(() -> {
            var endpoint = endpoints.findByPublicIdAndMerchantIdForUpdate(publicId, merchantId).orElseThrow();
            endpoint.disable(now.get());
            endpoints.save(endpoint);
            return null;
        });
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
