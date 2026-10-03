package com.flowpay.backend.webhook.infrastructure.id;

import org.junit.jupiter.api.Test;
import java.util.HashSet;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;

class UlidWebhookDeliveryIdentityGeneratorsTest {
    @Test
    void generatesDistinctPrefixedEventAndDeliveryIdentities() {
        var events = new UlidWebhookEventPublicIdGenerator();
        var deliveries = new UlidWebhookDeliveryPublicIdGenerator();
        Set<String> eventIds = new HashSet<>();
        Set<String> deliveryIds = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            String eventId = events.nextId();
            String deliveryId = deliveries.nextId();
            assertThat(eventId).matches("evt_[0-9A-HJKMNP-TV-Z]{26}");
            assertThat(deliveryId).matches("wdl_[0-9A-HJKMNP-TV-Z]{26}");
            eventIds.add(eventId);
            deliveryIds.add(deliveryId);
        }
        assertThat(eventIds).hasSize(1000);
        assertThat(deliveryIds).hasSize(1000);
    }
}
