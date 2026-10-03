package com.flowpay.backend.webhook.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;

class WebhookDeliveryAttemptTest {
    private static final Instant NOW = Instant.parse("2026-10-03T10:00:00.123456Z");

    @Test
    void opensWithoutResultAndCompletesExactlyOnce() {
        WebhookDeliveryAttempt attempt = WebhookDeliveryAttempt.open(10, 2, NOW);
        assertThat(attempt.internalId()).isNull();
        assertThat(attempt.deliveryId()).isEqualTo(10);
        assertThat(attempt.attemptNo()).isEqualTo(2);
        assertThat(attempt.startedAt()).isEqualTo(NOW);
        assertThat(attempt.createdAt()).isEqualTo(NOW);
        assertThat(attempt.finishedAt()).isNull();
        assertThat(attempt.httpStatus()).isNull();
        assertThat(attempt.durationMs()).isNull();
        assertThat(attempt.errorMessage()).isNull();
        attempt.complete(204, 10, null, NOW.plusMillis(10));
        assertThat(attempt.finishedAt()).isEqualTo(NOW.plusMillis(10));
        assertThat(attempt.httpStatus()).isEqualTo(204);
        assertThat(attempt.durationMs()).isEqualTo(10);
        assertThatThrownBy(() -> attempt.complete(500, 20, null, NOW.plusMillis(20)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(attempt.httpStatus()).isEqualTo(204);
    }

    @Test
    void retainsNormalizedTimeoutAndLeaseExpiryWithoutInventingHttpStatus() {
        WebhookDeliveryAttempt attempt = WebhookDeliveryAttempt.open(10, 1, NOW);
        attempt.complete(null, null, " DELIVERY_LEASE_EXPIRED ", NOW.plusSeconds(30));
        assertThat(attempt.httpStatus()).isNull();
        assertThat(attempt.durationMs()).isNull();
        assertThat(attempt.errorMessage()).isEqualTo("DELIVERY_LEASE_EXPIRED");
        WebhookDeliveryAttempt read = WebhookDeliveryAttempt.rehydrate(1, attempt.deliveryId(), attempt.attemptNo(),
                attempt.startedAt(), attempt.finishedAt(), attempt.httpStatus(), attempt.durationMs(),
                attempt.errorMessage(), attempt.createdAt());
        assertThat(read).usingRecursiveComparison().ignoringFields("internalId").isEqualTo(attempt);
        assertThatThrownBy(() -> read.complete(200, 1, null, NOW.plusSeconds(31)))
                .isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {99, 600, -1})
    void rejectsInvalidHttpStatusWithoutClosingTheAttempt(int status) {
        WebhookDeliveryAttempt attempt = WebhookDeliveryAttempt.open(10, 1, NOW);
        assertThatThrownBy(() -> attempt.complete(status, 1, null, NOW.plusMillis(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(attempt.finishedAt()).isNull();
    }

    @Test
    void rejectsInvalidIdentityTimeDurationOrMissingOutcome() {
        assertThatThrownBy(() -> WebhookDeliveryAttempt.open(0, 1, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WebhookDeliveryAttempt.open(1, 0, NOW)).isInstanceOf(IllegalArgumentException.class);
        WebhookDeliveryAttempt attempt = WebhookDeliveryAttempt.open(10, 1, NOW);
        assertThatThrownBy(() -> attempt.complete(200, -1, null, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> attempt.complete(200, 0, null, NOW.minusNanos(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> attempt.complete(null, 0, " ", NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> attempt.complete(200, 0, null, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> WebhookDeliveryAttempt.rehydrate(1, 10, 1, NOW, null, 200, null, null, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(attempt.finishedAt()).isNull();
    }
}
