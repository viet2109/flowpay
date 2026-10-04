package com.flowpay.backend.webhook.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.Instant;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.*;

class WebhookDeliveryTest {
    private static final Instant NOW = Instant.parse("2026-10-03T10:00:00.123456Z");

    @Test
    void expiryRequiresCurrentAttemptAndElapsedLeaseBeforeChangingState() {
        var delivery = create();
        assertThatThrownBy(() -> delivery.expireLease(1, NOW.plusSeconds(40), NOW)).isInstanceOf(IllegalStateException.class);
        delivery.claim(NOW, NOW.plusSeconds(30));
        assertThatThrownBy(() -> delivery.expireLease(1, NOW.plusSeconds(40), NOW.plusSeconds(29)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> delivery.expireLease(2, NOW.plusSeconds(40), NOW.plusSeconds(30)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> delivery.expireLease(1, NOW.plusSeconds(29), NOW.plusSeconds(30)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(delivery.status()).isEqualTo(WebhookDeliveryStatus.DELIVERING);
        assertThat(delivery.leaseExpiresAt()).isEqualTo(NOW.plusSeconds(30));
        delivery.expireLease(1, NOW.plusSeconds(40), NOW.plusSeconds(30));
        assertThat(delivery.status()).isEqualTo(WebhookDeliveryStatus.RETRYING);
        assertThat(delivery.lastError()).isEqualTo("DELIVERY_LEASE_EXPIRED");
        assertThat(delivery.lastHttpStatus()).isNull();
        assertThat(delivery.leaseExpiresAt()).isNull();
        assertThat(delivery.attemptCount()).isOne();
    }

    @Test
    void expiredLeaseWithoutNextScheduleBecomesDeadAndFencesLateSuccess() {
        var delivery = create();
        delivery.claim(NOW, NOW.plusSeconds(30));
        delivery.expireLease(1, null, NOW.plusSeconds(30));
        assertThat(delivery.status()).isEqualTo(WebhookDeliveryStatus.DEAD);
        assertThat(delivery.nextAttemptAt()).isNull();
        assertThat(delivery.leaseExpiresAt()).isNull();
        assertThat(delivery.lastError()).isEqualTo("DELIVERY_LEASE_EXPIRED");
        assertThatThrownBy(() -> delivery.markDelivered(1, 200, NOW.plusSeconds(31))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void boundsDeliveryDiagnosticWithoutBreakingUnicodeOrChangingFailureValidation() {
        var delivery = create();
        delivery.claim(NOW, NOW.plusSeconds(30));
        delivery.scheduleRetry(1, 503, " " + "x".repeat(511) + "😀tail ", NOW.plusSeconds(10), NOW);
        assertThat(delivery.lastError()).isEqualTo("x".repeat(511));
        delivery.claim(NOW.plusSeconds(10), NOW.plusSeconds(40));
        delivery.markDead(2, null, " x".repeat(600), NOW.plusSeconds(10));
        assertThat(delivery.lastError()).hasSize(512);
    }

    @Test
    void createsDuePendingAndClaimsWithLeaseAndFencingToken() {
        WebhookDelivery delivery = create();
        assertThat(delivery.internalId()).isNull();
        assertThat(delivery.version()).isZero();
        assertThat(delivery.status()).isEqualTo(WebhookDeliveryStatus.PENDING);
        assertThat(delivery.attemptCount()).isZero();
        assertThat(delivery.nextAttemptAt()).isEqualTo(NOW);
        assertThat(delivery.leaseExpiresAt()).isNull();
        assertThat(delivery.deliveredAt()).isNull();
        assertThat(delivery.claim(NOW, NOW.plusSeconds(30))).isOne();
        assertThat(delivery.status()).isEqualTo(WebhookDeliveryStatus.DELIVERING);
        assertThat(delivery.attemptCount()).isOne();
        assertThat(delivery.nextAttemptAt()).isNull();
        assertThat(delivery.leaseExpiresAt()).isEqualTo(NOW.plusSeconds(30));
        assertThat(delivery.publicId()).isEqualTo("wdl_one");
        assertThat(delivery.webhookEventId()).isEqualTo(1);
        assertThat(delivery.webhookEndpointId()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 204, 299})
    void successIsTerminalAndClearsLeaseAndFailureMetadata(int httpStatus) {
        WebhookDelivery delivery = create();
        delivery.claim(NOW, NOW.plusSeconds(30));
        delivery.scheduleRetry(1, 503, " HTTP_SERVER_ERROR ", NOW.plusSeconds(10), NOW.plusSeconds(1));
        delivery.claim(NOW.plusSeconds(10), NOW.plusSeconds(40));
        delivery.markDelivered(2, httpStatus, NOW.plusSeconds(11));
        assertThat(delivery.status()).isEqualTo(WebhookDeliveryStatus.DELIVERED);
        assertThat(delivery.deliveredAt()).isEqualTo(NOW.plusSeconds(11));
        assertThat(delivery.lastHttpStatus()).isEqualTo(httpStatus);
        assertThat(delivery.lastError()).isNull();
        assertThat(delivery.nextAttemptAt()).isNull();
        assertThat(delivery.leaseExpiresAt()).isNull();
        assertThatThrownBy(() -> delivery.markDelivered(2, httpStatus, NOW.plusSeconds(12)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> delivery.retryManually(NOW.plusSeconds(12))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> delivery.claim(NOW.plusSeconds(12), NOW.plusSeconds(42)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void retryDeadAndManualRetryPreserveHistoryAndFenceOldAttemptResults() {
        WebhookDelivery delivery = create();
        delivery.claim(NOW, NOW.plusSeconds(30));
        delivery.markDead(1, null, " DELIVERY_TIMEOUT ", NOW.plusSeconds(1));
        assertThat(delivery.status()).isEqualTo(WebhookDeliveryStatus.DEAD);
        assertThat(delivery.lastError()).isEqualTo("DELIVERY_TIMEOUT");
        assertThat(delivery.nextAttemptAt()).isNull();
        assertThat(delivery.leaseExpiresAt()).isNull();
        delivery.retryManually(NOW.plusSeconds(2));
        assertThat(delivery.attemptCount()).isOne();
        assertThat(delivery.lastError()).isEqualTo("DELIVERY_TIMEOUT");
        assertThat(delivery.nextAttemptAt()).isEqualTo(NOW.plusSeconds(2));
        assertThat(delivery.claim(NOW.plusSeconds(2), NOW.plusSeconds(32))).isEqualTo(2);
        assertThatThrownBy(() -> delivery.markDelivered(1, 200, NOW.plusSeconds(3)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> delivery.markDead(1, 500, null, NOW.plusSeconds(3)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> delivery.scheduleRetry(1, 500, null, NOW.plusSeconds(10), NOW.plusSeconds(3)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(delivery.status()).isEqualTo(WebhookDeliveryStatus.DELIVERING);
        delivery.markDelivered(2, 200, NOW.plusSeconds(3));
    }

    @Test
    void rejectsInvalidClaimsWithoutPartiallyMutatingState() {
        WebhookDelivery delivery = create();
        assertThatThrownBy(() -> delivery.claim(NOW.minusSeconds(1), NOW.plusSeconds(30)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> delivery.claim(NOW, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> delivery.claim(NOW, null)).isInstanceOf(NullPointerException.class);
        assertThat(delivery.status()).isEqualTo(WebhookDeliveryStatus.PENDING);
        assertThat(delivery.attemptCount()).isZero();
        delivery.claim(NOW, NOW.plusSeconds(30));
        delivery.scheduleRetry(1, 500, null, NOW.plusSeconds(10), NOW.plusSeconds(1));
        assertThatThrownBy(() -> delivery.claim(NOW.plusSeconds(9), NOW.plusSeconds(40)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(delivery.status()).isEqualTo(WebhookDeliveryStatus.RETRYING);
        assertThat(delivery.attemptCount()).isOne();
        delivery.claim(NOW.plusSeconds(10), NOW.plusSeconds(40));
        assertThatThrownBy(() -> delivery.claim(NOW.plusSeconds(11), NOW.plusSeconds(41)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void validatesFencedResultsBeforeChangingState() {
        WebhookDelivery delivery = create();
        assertThatThrownBy(() -> delivery.markDelivered(1, 200, NOW)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> delivery.retryManually(NOW)).isInstanceOf(IllegalStateException.class);
        delivery.claim(NOW, NOW.plusSeconds(30));
        for (int code : new int[]{99, 300, 500, 600}) {
            assertThatThrownBy(() -> delivery.markDelivered(1, code, NOW.plusSeconds(1)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> delivery.scheduleRetry(1, 503, null, NOW, NOW.plusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> delivery.markDead(1, null, " ", NOW.plusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> delivery.markDead(1, 200, null, NOW.plusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> delivery.markDelivered(0, 200, NOW.plusSeconds(1)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(delivery.status()).isEqualTo(WebhookDeliveryStatus.DELIVERING);
        assertThat(delivery.leaseExpiresAt()).isEqualTo(NOW.plusSeconds(30));
    }

    @Test
    void stoppingScheduledDeliveryForDisabledEndpointDoesNotInventAnAttempt() {
        WebhookDelivery delivery = create();
        delivery.stopForDisabledEndpoint(NOW);
        assertThat(delivery.status()).isEqualTo(WebhookDeliveryStatus.DEAD);
        assertThat(delivery.attemptCount()).isZero();
        assertThat(delivery.nextAttemptAt()).isNull();
        assertThat(delivery.lastError()).isEqualTo("ENDPOINT_DISABLED");
        WebhookDelivery inFlight = create();
        inFlight.claim(NOW, NOW.plusSeconds(30));
        assertThatThrownBy(() -> inFlight.stopForDisabledEndpoint(NOW)).isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @MethodSource("timestampStates")
    void rehydrationProtectsEveryStateTimestampCombination(WebhookDeliveryStatus status, int mask) {
        int validMask = switch (status) {
            case PENDING, RETRYING -> 1;
            case DELIVERING -> 2;
            case DELIVERED -> 4;
            case DEAD -> 0;
        };
        if (mask == validMask) {
            WebhookDelivery read = rehydrate(status, mask, 1, 3);
            assertThat(read.internalId()).isEqualTo(10);
            assertThat(read.version()).isEqualTo(3);
        } else {
            assertThatThrownBy(() -> rehydrate(status, mask, 1, 3)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void rejectsInvalidIdentityCountsVersionsAndAttemptOverflow() {
        for (String id : new String[]{"wdl_", "evt_one", "wdl_" + "x".repeat(61)}) {
            assertThatThrownBy(() -> WebhookDelivery.create(id, 1, 2, NOW)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> WebhookDelivery.create("wdl_one", 0, 2, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WebhookDelivery.create("wdl_one", 1, 0, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> rehydrate(WebhookDeliveryStatus.PENDING, 1, -1, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> rehydrate(WebhookDeliveryStatus.PENDING, 1, 0, -1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> rehydrate(WebhookDeliveryStatus.DELIVERING, 2, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        WebhookDelivery full = rehydrate(WebhookDeliveryStatus.RETRYING, 1, Integer.MAX_VALUE, 0);
        assertThatThrownBy(() -> full.claim(NOW, NOW.plusSeconds(30))).isInstanceOf(ArithmeticException.class);
        assertThat(full.status()).isEqualTo(WebhookDeliveryStatus.RETRYING);
        assertThat(full.attemptCount()).isEqualTo(Integer.MAX_VALUE);
    }

    private static WebhookDelivery create() {
        return WebhookDelivery.create("wdl_one", 1, 2, NOW);
    }

    private static WebhookDelivery rehydrate(WebhookDeliveryStatus status, int mask, int attempts, long version) {
        return WebhookDelivery.rehydrate(10, "wdl_one", 1, 2, status, attempts,
                (mask & 1) != 0 ? NOW : null, (mask & 2) != 0 ? NOW.plusSeconds(30) : null,
                (mask & 4) != 0 ? NOW : null, null, null, NOW, NOW, version);
    }

    private static Stream<Arguments> timestampStates() {
        return Stream.of(WebhookDeliveryStatus.values())
                .flatMap(status -> IntStream.range(0, 8).mapToObj(mask -> Arguments.of(status, mask)));
    }
}
