package com.flowpay.backend.webhook.infrastructure.id;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UlidWebhookEndpointPublicIdGeneratorTest {

    @Test
    void shouldGeneratePrefixedUniquePublicIds() {
        UlidWebhookEndpointPublicIdGenerator generator =
                new UlidWebhookEndpointPublicIdGenerator();

        String first = generator.nextId();
        String second = generator.nextId();

        assertThat(first).startsWith("wep_").hasSize(30);
        assertThat(second).startsWith("wep_").hasSize(30).isNotEqualTo(first);
    }
}
