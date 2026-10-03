package com.flowpay.backend.webhook.application;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class ScheduledWebhookDeliveryFailurePolicyTest {
    private static final List<Duration> DELAYS = List.of(Duration.ofSeconds(10), Duration.ofSeconds(30),
            Duration.ofMinutes(2), Duration.ofMinutes(10), Duration.ofHours(1));
    private static final Instant NOW = Instant.parse("2026-10-04T00:00:00Z");

    @Test
    void schedulesFiveBoundedDeterministicRetriesAndSixthFailureIsDead() {
        var config = new WebhookDeliveryRetryProperties(DELAYS, new BigDecimal("0.20"));
        var policy = new ScheduledWebhookDeliveryFailurePolicy(config);
        for (int failed = 1; failed <= 5; failed++) {
            var next = policy.nextAttemptAt("wdl_example", failed, NOW).orElseThrow();
            assertThat(next).isBetween(NOW.plus(DELAYS.get(failed - 1)), NOW.plusNanos(DELAYS.get(failed - 1).toNanos() * 12 / 10));
            assertThat(new ScheduledWebhookDeliveryFailurePolicy(config).nextAttemptAt("wdl_example", failed, NOW)).contains(next);
        }
        assertThat(policy.nextAttemptAt("wdl_example", 6, NOW)).isEmpty();
        assertThat(policy.nextAttemptAt("wdl_example", 8, NOW)).isEmpty();
        assertThatThrownBy(() -> policy.nextAttemptAt("wdl_example", 0, NOW)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void zeroJitterUsesExactBaseAndDifferentIdentitiesSpreadRetryTimes() {
        var noJitter = new ScheduledWebhookDeliveryFailurePolicy(new WebhookDeliveryRetryProperties(DELAYS, BigDecimal.ZERO));
        assertThat(noJitter.nextAttemptAt("wdl_example", 1, NOW)).contains(NOW.plusSeconds(10));
        var jitter = new ScheduledWebhookDeliveryFailurePolicy(new WebhookDeliveryRetryProperties(DELAYS, new BigDecimal("0.2")));
        assertThat(jitter.nextAttemptAt("wdl_one", 1, NOW)).isNotEqualTo(jitter.nextAttemptAt("wdl_two", 1, NOW));
    }
}
