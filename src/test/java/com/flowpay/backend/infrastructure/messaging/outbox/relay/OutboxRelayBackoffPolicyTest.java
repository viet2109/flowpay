package com.flowpay.backend.infrastructure.messaging.outbox.relay;

import com.flowpay.backend.infrastructure.messaging.FlowPayMessagingProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutboxRelayBackoffPolicyTest {

    private final OutboxRelayBackoffPolicy policy = new OutboxRelayBackoffPolicy(
            properties(Duration.ofSeconds(1), Duration.ofSeconds(8))
    );

    @Test
    void shouldApplyDeterministicEqualJitterWithinCappedExponentialWindow() {
        assertWithinWindow("ievt_backoff", 0, Duration.ofSeconds(1));
        assertWithinWindow("ievt_backoff", 1, Duration.ofSeconds(2));
        assertWithinWindow("ievt_backoff", 2, Duration.ofSeconds(4));
        assertWithinWindow("ievt_backoff", 3, Duration.ofSeconds(8));
        assertWithinWindow("ievt_backoff", 1_000_000, Duration.ofSeconds(8));
    }

    @Test
    void shouldRemainStableForSameAttemptAndSpreadDifferentEvents() {
        Duration first = policy.delayAfterFailure("ievt_stable", 2);

        assertThat(policy.delayAfterFailure("ievt_stable", 2)).isEqualTo(first);
        Set<Duration> delays = IntStream.range(0, 32)
                .mapToObj(index -> policy.delayAfterFailure("ievt_" + index, 2))
                .collect(Collectors.toSet());
        assertThat(delays).hasSizeGreaterThan(1);
    }

    @Test
    void shouldKeepJitterOverflowSafeForVeryLargeDurations() {
        Duration max = Duration.ofSeconds(Long.MAX_VALUE);
        OutboxRelayBackoffPolicy largePolicy = new OutboxRelayBackoffPolicy(
                properties(
                        Duration.ofSeconds(Long.MAX_VALUE / 2 + 1),
                        max
                )
        );

        Duration delay = largePolicy.delayAfterFailure("ievt_large", 1);

        assertThat(delay).isBetween(max.dividedBy(2), max);
    }

    @Test
    void shouldRejectInvalidInput() {
        assertThatThrownBy(() -> policy.delayAfterFailure("ievt_invalid", -1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.delayAfterFailure(" ", 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.delayAfterFailure(null, 0))
                .isInstanceOf(NullPointerException.class);
    }

    private void assertWithinWindow(
            String eventId,
            int previousFailureCount,
            Duration exponentialCeiling
    ) {
        assertThat(policy.delayAfterFailure(eventId, previousFailureCount))
                .isBetween(exponentialCeiling.dividedBy(2), exponentialCeiling);
    }

    private static FlowPayMessagingProperties properties(
            Duration initialBackoff,
            Duration maxBackoff
    ) {
        return new FlowPayMessagingProperties(
                new FlowPayMessagingProperties.Topology(
                        "flowpay.events",
                        "flowpay.ledger.events",
                        "flowpay.events.dlx",
                        "flowpay.ledger.events.dlq",
                        "ledger.dead"
                ),
                new FlowPayMessagingProperties.LedgerConsumer(
                        true,
                        new FlowPayMessagingProperties.Retry(
                                3,
                                Duration.ofMillis(500),
                                2.0,
                                Duration.ofSeconds(5)
                        )
                ),
                new FlowPayMessagingProperties.Outbox(
                        Duration.ofSeconds(5),
                        new FlowPayMessagingProperties.Relay(
                                false,
                                Duration.ofSeconds(1),
                                100,
                                initialBackoff,
                                maxBackoff
                        )
                )
        );
    }
}
