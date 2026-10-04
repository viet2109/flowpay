package com.flowpay.backend.infrastructure.messaging;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.assertj.core.api.Assertions.*;

class WebhookMessagingPropertiesTest {
    @Test
    void normalizesTopologyAndRejectsDuplicateOrEmptyNames() {
        var topology = new WebhookMessagingProperties.Topology(" events ", " dead ", " webhook.dead ");
        assertThat(topology.webhookQueue()).isEqualTo("events");
        assertThat(topology.webhookDeadLetterQueue()).isEqualTo("dead");
        assertThat(topology.webhookDeadLetterRoutingKey()).isEqualTo("webhook.dead");
        assertThatThrownBy(() -> new WebhookMessagingProperties.Topology("same", "same", "dead"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WebhookMessagingProperties.Topology(" ", "dead", "key"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WebhookMessagingProperties.Topology("events", "dead", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void requiresExplicitBoundedRetryAndTopology() {
        var retry = new FlowPayMessagingProperties.Retry(3, Duration.ofMillis(500), 2, Duration.ofSeconds(5));
        var topology = new WebhookMessagingProperties.Topology("events", "dead", "webhook.dead");
        assertThat(new WebhookMessagingProperties(topology, new WebhookMessagingProperties.Consumer(true, retry))
                .webhookConsumer().retry()).isEqualTo(retry);
        assertThatThrownBy(() -> new WebhookMessagingProperties.Consumer(true, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new WebhookMessagingProperties(null, new WebhookMessagingProperties.Consumer(true, retry)))
                .isInstanceOf(NullPointerException.class);
    }
}
