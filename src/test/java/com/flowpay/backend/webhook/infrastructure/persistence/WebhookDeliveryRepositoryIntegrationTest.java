package com.flowpay.backend.webhook.infrastructure.persistence;

import com.flowpay.backend.testing.PostgresIntegrationTest;
import com.flowpay.backend.webhook.application.WebhookDeliveryAttemptRepository;
import com.flowpay.backend.webhook.application.WebhookDeliveryRepository;
import com.flowpay.backend.webhook.application.WebhookEndpointRepository;
import com.flowpay.backend.webhook.application.WebhookEventRepository;
import com.flowpay.backend.webhook.domain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class WebhookDeliveryRepositoryIntegrationTest extends PostgresIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-03T10:00:00.123456Z");
    @Autowired private WebhookEventRepository events;
    @Autowired private WebhookDeliveryRepository deliveries;
    @Autowired private WebhookDeliveryAttemptRepository attempts;
    @Autowired private WebhookEndpointRepository endpoints;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;
    private long merchantId;
    private long endpointId;

    @BeforeEach
    void cleanData() {
        jdbc.execute("TRUNCATE TABLE merchants, users RESTART IDENTITY CASCADE");
        merchantId = merchant("mrc_webhook_persistence");
        endpointId = tx(() -> endpoints.save(WebhookEndpoint.create("wep_one", merchantId,
                "https://example.com/webhooks", "encrypted", List.of(WebhookEventType.PAYMENT_SUCCEEDED), NOW)))
                .internalId();
    }

    @Test
    void insertsImmutableJsonSnapshotAndLooksUpBySourceAndInternalIdentity() {
        WebhookEvent saved = tx(() -> events.tryInsert(event("one")).orElseThrow());
        assertThat(saved.internalId()).isPositive();
        assertThat(saved.resourceId()).isEqualTo("pi_one");
        assertThat(saved.eventType()).isEqualTo(WebhookEventType.PAYMENT_SUCCEEDED);
        assertThat(saved.occurredAt()).isEqualTo(NOW);
        assertThat(saved.createdAt()).isEqualTo(NOW.plusSeconds(1));
        assertThat(saved.payload()).isEqualTo(jdbc.queryForObject(
                "SELECT payload::text FROM webhook_events WHERE id = ?", String.class, saved.internalId()));
        assertThat(events.findBySourceEventId("source_one").orElseThrow())
                .usingRecursiveComparison().isEqualTo(saved);
        assertThat(events.findByInternalId(saved.internalId()).orElseThrow())
                .usingRecursiveComparison().isEqualTo(saved);
        assertThat(events.findBySourceEventId("unknown")).isEmpty();
        assertThatThrownBy(() -> tx(() -> events.tryInsert(saved)))
                .isInstanceOf(InvalidDataAccessApiUsageException.class)
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sourceDedupeDoesNotOverwriteFactsOrAbortTheCallerTransaction() {
        WebhookEvent saved = tx(() -> events.tryInsert(event("one")).orElseThrow());
        WebhookEvent conflicting = WebhookEvent.create("evt_conflict", "source_one", merchantId,
                WebhookEventType.PAYMENT_FAILED, WebhookResourceType.PAYMENT_INTENT, "pi_other",
                "{\"id\":\"evt_conflict\"}", NOW.plusSeconds(1), NOW.plusSeconds(2));
        tx(() -> {
            assertThat(events.tryInsert(conflicting)).isEmpty();
            assertThat(events.tryInsert(event("two"))).isPresent();
            return null;
        });
        assertThat(events.findBySourceEventId("source_one").orElseThrow())
                .usingRecursiveComparison().isEqualTo(saved);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM webhook_events", Long.class)).isEqualTo(2);
        WebhookEvent duplicatePublicId = WebhookEvent.create("evt_one", "source_other", merchantId,
                WebhookEventType.PAYMENT_SUCCEEDED, WebhookResourceType.PAYMENT_INTENT,
                "pi_other", "{}", NOW, NOW);
        assertThatThrownBy(() -> tx(() -> events.tryInsert(duplicatePublicId)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(events.findBySourceEventId("source_other")).isEmpty();
    }

    @Test
    void rollsBackEventDeliveryAndAttemptWritesTogether() {
        assertThatThrownBy(() -> tx(() -> {
            WebhookEvent event = events.tryInsert(event("atomic")).orElseThrow();
            WebhookDelivery delivery = deliveries.save(WebhookDelivery.create("wdl_atomic", event.internalId(), endpointId, NOW));
            delivery.claim(NOW, NOW.plusSeconds(30));
            deliveries.save(delivery);
            attempts.insert(WebhookDeliveryAttempt.open(delivery.internalId(), 1, NOW));
            throw new IllegalStateException("rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM webhook_events", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM webhook_deliveries", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM webhook_delivery_attempts", Long.class)).isZero();
    }

    @ParameterizedTest
    @EnumSource(WebhookDeliveryStatus.class)
    void roundTripsEveryDeliveryStateAndItsNullableMetadata(WebhookDeliveryStatus status) {
        WebhookDelivery delivery = newDelivery("state", NOW);
        if (status != WebhookDeliveryStatus.PENDING) {
            delivery.claim(NOW, NOW.plusSeconds(30));
            switch (status) {
                case DELIVERED -> delivery.markDelivered(1, 204, NOW.plusSeconds(1));
                case RETRYING -> delivery.scheduleRetry(1, null, "DELIVERY_TIMEOUT", NOW.plusSeconds(10), NOW.plusSeconds(1));
                case DEAD -> delivery.markDead(1, 503, "HTTP_SERVER_ERROR", NOW.plusSeconds(1));
                default -> { }
            }
        }
        WebhookDelivery saved = tx(() -> deliveries.save(delivery));
        WebhookDelivery read = deliveries.findByInternalId(saved.internalId()).orElseThrow();
        assertThat(read).usingRecursiveComparison().isEqualTo(saved);
        assertThat(read.status()).isEqualTo(status);
        assertThat(read.version()).isEqualTo(status == WebhookDeliveryStatus.PENDING ? 0 : 1);
        assertThat(read.createdAt()).isEqualTo(NOW);
        assertThat(deliveries.findByPublicIdAndMerchantId("wdl_state", merchantId)).isPresent();
        assertThat(deliveries.findByPublicIdAndMerchantId("wdl_state", merchantId + 1)).isEmpty();
    }

    @Test
    void ownedLookupRequiresBothEventAndEndpointTenant() {
        long otherMerchant = merchant("mrc_other");
        long otherEndpoint = tx(() -> endpoints.save(WebhookEndpoint.create("wep_other", otherMerchant,
                "https://other.example", "encrypted", List.of(WebhookEventType.PAYMENT_SUCCEEDED), NOW))).internalId();
        WebhookEvent event = tx(() -> events.tryInsert(event("mixed_owner")).orElseThrow());
        // V011 has separate FKs. A corrupt mixed-owner pair must fail closed on public lookup.
        tx(() -> deliveries.save(WebhookDelivery.create("wdl_mixed", event.internalId(), otherEndpoint, NOW)));
        assertThat(deliveries.findByPublicIdAndMerchantId("wdl_mixed", merchantId)).isEmpty();
        assertThat(deliveries.findByPublicIdAndMerchantId("wdl_mixed", otherMerchant)).isEmpty();
        assertThat(deliveries.findByPublicIdAndMerchantId("wdl_unknown", merchantId)).isEmpty();
    }

    @Test
    void queriesOnlyDueSchedulesAndExpiredLeasesWithStableOrderingAndLimits() {
        WebhookDelivery first = newDelivery("due_first", NOW);
        WebhookDelivery second = newDelivery("due_second", NOW);
        WebhookDelivery retry = newDelivery("retry", NOW);
        retry.claim(NOW, NOW.plusSeconds(30));
        retry.scheduleRetry(1, 500, null, NOW.plusSeconds(10), NOW.plusSeconds(1));
        tx(() -> deliveries.save(retry));
        newDelivery("future", NOW.plusSeconds(100));
        WebhookDelivery expired = newDelivery("expired", NOW);
        expired.claim(NOW, NOW.plusSeconds(30));
        tx(() -> deliveries.save(expired));
        WebhookDelivery activeLease = newDelivery("active_lease", NOW);
        activeLease.claim(NOW, NOW.plusSeconds(100));
        tx(() -> deliveries.save(activeLease));
        WebhookDelivery delivered = newDelivery("delivered", NOW);
        delivered.claim(NOW, NOW.plusSeconds(30));
        delivered.markDelivered(1, 200, NOW.plusSeconds(1));
        tx(() -> deliveries.save(delivered));
        WebhookDelivery dead = newDelivery("dead", NOW);
        dead.stopForDisabledEndpoint(NOW);
        tx(() -> deliveries.save(dead));
        assertThat(deliveries.findDue(NOW, 10)).extracting(WebhookDelivery::internalId)
                .containsExactly(first.internalId(), second.internalId());
        assertThat(deliveries.findDue(NOW.plusSeconds(10), 10)).extracting(WebhookDelivery::publicId)
                .containsExactly("wdl_due_first", "wdl_due_second", "wdl_retry");
        assertThat(deliveries.findDue(NOW.plusSeconds(10), 1)).extracting(WebhookDelivery::publicId)
                .containsExactly("wdl_due_first");
        assertThat(deliveries.findExpiredLeases(NOW.plusSeconds(29), 10)).isEmpty();
        assertThat(deliveries.findExpiredLeases(NOW.plusSeconds(30), 1)).extracting(WebhookDelivery::internalId)
                .containsExactly(expired.internalId());
        assertThat(deliveries.findExpiredLeases(NOW.plusSeconds(100), 10)).extracting(WebhookDelivery::publicId)
                .containsExactly("wdl_expired", "wdl_active_lease");
        assertThatThrownBy(() -> deliveries.findDue(NOW, 0)).isInstanceOf(InvalidDataAccessApiUsageException.class)
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> deliveries.findExpiredLeases(NOW, -1)).isInstanceOf(InvalidDataAccessApiUsageException.class)
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> deliveries.findDue(null, 1)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void optimisticLockRejectsCompetingClaims() {
        WebhookDelivery original = newDelivery("claims", NOW);
        WebhookDelivery first = deliveries.findByInternalId(original.internalId()).orElseThrow();
        WebhookDelivery stale = deliveries.findByInternalId(original.internalId()).orElseThrow();
        first.claim(NOW, NOW.plusSeconds(30));
        stale.claim(NOW, NOW.plusSeconds(40));
        WebhookDelivery winner = tx(() -> deliveries.save(first));
        assertThat(winner.version()).isOne();
        assertThatThrownBy(() -> tx(() -> deliveries.save(stale)))
                .isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(deliveries.findByInternalId(original.internalId()).orElseThrow())
                .usingRecursiveComparison().isEqualTo(winner);
    }

    @Test
    void rejectsLatePersistedResultAfterLeaseRecoveryAndASecondClaim() {
        WebhookDelivery original = newDelivery("fenced", NOW);
        original.claim(NOW, NOW.plusSeconds(30));
        WebhookDelivery claimed = tx(() -> deliveries.save(original));
        WebhookDelivery stale = deliveries.findByInternalId(claimed.internalId()).orElseThrow();
        WebhookDelivery recovered = deliveries.findByInternalId(claimed.internalId()).orElseThrow();
        recovered.scheduleRetry(1, null, "DELIVERY_LEASE_EXPIRED", NOW.plusSeconds(30), NOW.plusSeconds(30));
        WebhookDelivery retrying = tx(() -> deliveries.save(recovered));
        retrying.claim(NOW.plusSeconds(30), NOW.plusSeconds(60));
        WebhookDelivery newer = tx(() -> deliveries.save(retrying));
        stale.markDelivered(1, 200, NOW.plusSeconds(31));
        assertThatThrownBy(() -> tx(() -> deliveries.save(stale)))
                .isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(newer.attemptCount()).isEqualTo(2);
        assertThat(newer.status()).isEqualTo(WebhookDeliveryStatus.DELIVERING);
        assertThat(deliveries.findByInternalId(claimed.internalId()).orElseThrow())
                .usingRecursiveComparison().isEqualTo(newer);
    }

    @Test
    void locksDeliveryRowWithinTheCallerTransaction() {
        WebhookDelivery original = newDelivery("locked", NOW);
        TransactionTemplate competitor = new TransactionTemplate(transactions);
        competitor.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx(() -> {
            assertThat(deliveries.findByInternalIdForUpdate(original.internalId())).isPresent();
            assertThatThrownBy(() -> competitor.execute(status -> jdbc.queryForObject(
                    "SELECT id FROM webhook_deliveries WHERE id = ? FOR UPDATE NOWAIT", Long.class, original.internalId())))
                    .isInstanceOfSatisfying(UncategorizedSQLException.class,
                            error -> assertThat(error.getSQLException().getSQLState()).isEqualTo("55P03"));
            return null;
        });
    }

    @Test
    void completesAttemptOnceAndRejectsStaleOrForgedHistoryWrites() {
        WebhookDelivery delivery = newDelivery("attempt", NOW);
        WebhookDeliveryAttempt first = tx(() -> attempts.insert(WebhookDeliveryAttempt.open(delivery.internalId(), 1, NOW)));
        WebhookDeliveryAttempt stale = attempts.findByDeliveryIdAndAttemptNo(delivery.internalId(), 1).orElseThrow();
        first.complete(204, 10, null, NOW.plusMillis(10));
        WebhookDeliveryAttempt saved = tx(() -> attempts.complete(first));
        assertThat(saved).usingRecursiveComparison().isEqualTo(first);
        stale.complete(500, 20, "HTTP_SERVER_ERROR", NOW.plusMillis(20));
        assertThatThrownBy(() -> tx(() -> attempts.complete(stale)))
                .isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(attempts.findByDeliveryIdAndAttemptNo(delivery.internalId(), 1).orElseThrow())
                .usingRecursiveComparison().isEqualTo(saved);
        assertThatThrownBy(() -> tx(() -> attempts.insert(saved))).isInstanceOf(InvalidDataAccessApiUsageException.class)
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tx(() -> attempts.complete(WebhookDeliveryAttempt.open(delivery.internalId(), 2, NOW))))
                .isInstanceOf(InvalidDataAccessApiUsageException.class)
                .hasRootCauseInstanceOf(IllegalArgumentException.class);

        WebhookDeliveryAttempt open = tx(() -> attempts.insert(WebhookDeliveryAttempt.open(delivery.internalId(), 2, NOW)));
        WebhookDeliveryAttempt forged = WebhookDeliveryAttempt.rehydrate(open.internalId(), open.deliveryId(), 2,
                NOW.plusMillis(1), NOW.plusMillis(10), 200, 9, null, NOW);
        assertThatThrownBy(() -> tx(() -> attempts.complete(forged))).isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(attempts.findByDeliveryIdAndAttemptNo(delivery.internalId(), 2).orElseThrow().finishedAt()).isNull();
    }

    @Test
    void returnsAttemptHistorySortedAndPreservesItAcrossManualRetry() {
        WebhookDelivery delivery = newDelivery("history", NOW);
        for (int no = 1; no <= 3; no++) {
            delivery.claim(NOW.plusSeconds(no), NOW.plusSeconds(no + 30));
            if (no < 3) {
                delivery.scheduleRetry(no, 500, "HTTP_SERVER_ERROR", NOW.plusSeconds(no + 1), NOW.plusSeconds(no));
            } else {
                delivery.markDead(no, 500, "HTTP_SERVER_ERROR", NOW.plusSeconds(no));
            }
        }
        WebhookDelivery dead = tx(() -> deliveries.save(delivery));
        for (int no : new int[]{3, 1, 2}) {
            tx(() -> attempts.insert(WebhookDeliveryAttempt.open(delivery.internalId(), no, NOW)));
        }
        assertThat(attempts.findAllByDeliveryId(delivery.internalId())).extracting(WebhookDeliveryAttempt::attemptNo)
                .containsExactly(1, 2, 3);
        assertThat(attempts.findAllByDeliveryId(9_999_999L)).isEmpty();
        assertThat(attempts.findByDeliveryIdAndAttemptNo(delivery.internalId(), 4)).isEmpty();
        assertThatThrownBy(() -> tx(() -> attempts.insert(WebhookDeliveryAttempt.open(delivery.internalId(), 1, NOW))))
                .isInstanceOf(DataIntegrityViolationException.class);
        dead.retryManually(NOW.plusSeconds(4));
        WebhookDelivery retried = tx(() -> deliveries.save(dead));
        assertThat(retried.attemptCount()).isEqualTo(3);
        assertThat(attempts.findAllByDeliveryId(delivery.internalId())).hasSize(3);
    }

    @Test
    void rollsBackAttemptCompletionAndDeliveryResultTogether() {
        WebhookDelivery delivery = newDelivery("result_rollback", NOW);
        delivery.claim(NOW, NOW.plusSeconds(30));
        WebhookDelivery claimed = tx(() -> deliveries.save(delivery));
        WebhookDeliveryAttempt open = tx(() -> attempts.insert(WebhookDeliveryAttempt.open(delivery.internalId(), 1, NOW)));
        assertThatThrownBy(() -> tx(() -> {
            open.complete(200, 10, null, NOW.plusMillis(10));
            attempts.complete(open);
            claimed.markDelivered(1, 200, NOW.plusMillis(10));
            deliveries.save(claimed);
            throw new IllegalStateException("rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(attempts.findByDeliveryIdAndAttemptNo(delivery.internalId(), 1).orElseThrow().finishedAt()).isNull();
        assertThat(deliveries.findByInternalId(delivery.internalId()).orElseThrow().status())
                .isEqualTo(WebhookDeliveryStatus.DELIVERING);
    }

    @Test
    void concurrentAttemptCompletionsHaveExactlyOneWinner() throws Exception {
        WebhookDelivery delivery = newDelivery("concurrent", NOW);
        tx(() -> attempts.insert(WebhookDeliveryAttempt.open(delivery.internalId(), 1, NOW)));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> completeConcurrently(delivery.internalId(), 200, ready, start));
            var second = executor.submit(() -> completeConcurrently(delivery.internalId(), 503, ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        } finally {
            start.countDown();
        }
        WebhookDeliveryAttempt winner = attempts.findByDeliveryIdAndAttemptNo(delivery.internalId(), 1).orElseThrow();
        assertThat(winner.finishedAt()).isEqualTo(NOW.plusMillis(10));
        assertThat(winner.httpStatus()).isIn(200, 503);
        assertThat(attempts.findAllByDeliveryId(delivery.internalId())).hasSize(1);
    }

    private boolean completeConcurrently(long deliveryId, int httpStatus, CountDownLatch ready, CountDownLatch start)
            throws InterruptedException {
        WebhookDeliveryAttempt attempt = attempts.findByDeliveryIdAndAttemptNo(deliveryId, 1).orElseThrow();
        attempt.complete(httpStatus, 10, null, NOW.plusMillis(10));
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("concurrent test start timed out");
        }
        try {
            tx(() -> attempts.complete(attempt));
            return true;
        } catch (OptimisticLockingFailureException expected) {
            return false;
        }
    }

    private WebhookDelivery newDelivery(String suffix, Instant createdAt) {
        return tx(() -> {
            WebhookEvent event = events.tryInsert(event(suffix)).orElseThrow();
            return deliveries.save(WebhookDelivery.create("wdl_" + suffix, event.internalId(), endpointId, createdAt));
        });
    }

    private WebhookEvent event(String suffix) {
        return WebhookEvent.create("evt_" + suffix, "source_" + suffix, merchantId,
                WebhookEventType.PAYMENT_SUCCEEDED, WebhookResourceType.PAYMENT_INTENT, "pi_" + suffix,
                "{\"id\":\"evt_" + suffix + "\",\"type\":\"payment.succeeded\",\"data\":{\"payment\":{\"id\":\"pi_" + suffix + "\"}}}",
                NOW, NOW.plusSeconds(1));
    }

    private long merchant(String publicId) {
        return jdbc.queryForObject("""
                INSERT INTO merchants (public_id, name, status, created_at, updated_at)
                VALUES (?, 'Webhook Store', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP) RETURNING id
                """, Long.class, publicId);
    }

    private <T> T tx(Supplier<T> action) {
        return new TransactionTemplate(transactions).execute(status -> action.get());
    }
}
