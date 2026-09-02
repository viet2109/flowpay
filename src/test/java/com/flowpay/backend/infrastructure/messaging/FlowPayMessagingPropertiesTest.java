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
                new FlowPayMessagingProperties.Outbox(timeout)
        );
    }
}
