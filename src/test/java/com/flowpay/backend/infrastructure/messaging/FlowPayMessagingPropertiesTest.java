package com.flowpay.backend.infrastructure.messaging;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FlowPayMessagingPropertiesTest {

    @Test
    void shouldNormalizeValidSettings() {
        FlowPayMessagingProperties properties = properties(
                " flowpay.events ",
                " flowpay.ledger.events ",
                " flowpay.events.dlx ",
                " flowpay.ledger.events.dlq ",
                " ledger.dead ",
                Duration.ofSeconds(5)
        );

        assertThat(properties.topology().exchange()).isEqualTo("flowpay.events");
        assertThat(properties.topology().ledgerQueue())
                .isEqualTo("flowpay.ledger.events");
        assertThat(properties.outbox().publisherConfirmTimeout())
                .isEqualTo(Duration.ofSeconds(5));
        assertThat(properties.outbox().relay().batchSize()).isEqualTo(100);
        assertThat(properties.ledgerConsumer().retry()).isEqualTo(
                new FlowPayMessagingProperties.Retry(
                        3,
                        Duration.ofMillis(500),
                        2.0,
                        Duration.ofSeconds(5)
                )
        );
    }

    @Test
    void shouldRejectUnsafeTopologyAndTimeoutSettings() {
        assertThatThrownBy(() -> properties(
                " ", "ledger", "dlx", "dlq", "dead", Duration.ofSeconds(1)
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(
                "events", "ledger", "events", "dlq", "dead", Duration.ofSeconds(1)
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(
                "events", "ledger", "dlx", "ledger", "dead", Duration.ofSeconds(1)
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(
                "events", "ledger", "dlx", "dlq", "dead", Duration.ZERO
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FlowPayMessagingProperties.Relay(
                true,
                Duration.ofSeconds(1),
                0,
                Duration.ofSeconds(1),
                Duration.ofSeconds(2)
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FlowPayMessagingProperties.Relay(
                true,
                Duration.ofSeconds(1),
                10,
                Duration.ofSeconds(2),
                Duration.ofSeconds(1)
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FlowPayMessagingProperties.Retry(
                0,
                Duration.ofMillis(500),
                2.0,
                Duration.ofSeconds(5)
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FlowPayMessagingProperties.Retry(
                3,
                Duration.ZERO,
                2.0,
                Duration.ofSeconds(5)
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FlowPayMessagingProperties.Retry(
                3,
                Duration.ofMillis(500),
                0.5,
                Duration.ofSeconds(5)
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FlowPayMessagingProperties.Retry(
                3,
                Duration.ofSeconds(2),
                2.0,
                Duration.ofSeconds(1)
        )).isInstanceOf(IllegalArgumentException.class);
    }

    private static FlowPayMessagingProperties properties(
            String exchange,
            String ledgerQueue,
            String deadLetterExchange,
            String deadLetterQueue,
            String deadLetterRoutingKey,
            Duration timeout
    ) {
        return new FlowPayMessagingProperties(
                new FlowPayMessagingProperties.Topology(
                        exchange,
                        ledgerQueue,
                        deadLetterExchange,
                        deadLetterQueue,
                        deadLetterRoutingKey
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
                        timeout,
                        new FlowPayMessagingProperties.Relay(
                                true,
                                Duration.ofSeconds(1),
                                100,
                                Duration.ofSeconds(1),
                                Duration.ofMinutes(1)
                        )
                )
        );
    }
}
