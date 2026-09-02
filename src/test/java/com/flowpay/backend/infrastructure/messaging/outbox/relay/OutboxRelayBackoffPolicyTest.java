package com.flowpay.backend.infrastructure.messaging.outbox.relay;

import com.flowpay.backend.infrastructure.messaging.FlowPayMessagingProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutboxRelayBackoffPolicyTest {

    private final OutboxRelayBackoffPolicy policy = new OutboxRelayBackoffPolicy(
            properties(Duration.ofSeconds(1), Duration.ofSeconds(8))
    );

    @Test
    void shouldApplyOverflowSafeExponentialBackoffAndCapIndefinitely() {
        assertThat(policy.delayAfterFailure(0)).isEqualTo(Duration.ofSeconds(1));
        assertThat(policy.delayAfterFailure(1)).isEqualTo(Duration.ofSeconds(2));
        assertThat(policy.delayAfterFailure(2)).isEqualTo(Duration.ofSeconds(4));
        assertThat(policy.delayAfterFailure(3)).isEqualTo(Duration.ofSeconds(8));
        assertThat(policy.delayAfterFailure(1_000_000))
                .isEqualTo(Duration.ofSeconds(8));
    }

    @Test
    void shouldRejectNegativeFailureCount() {
        assertThatThrownBy(() -> policy.delayAfterFailure(-1))
                .isInstanceOf(IllegalArgumentException.class);
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
