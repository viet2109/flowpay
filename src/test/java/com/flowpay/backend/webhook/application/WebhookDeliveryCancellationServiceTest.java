package com.flowpay.backend.webhook.application;

import com.flowpay.backend.webhook.domain.WebhookDelivery;
import com.flowpay.backend.webhook.domain.WebhookDeliveryStatus;
import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WebhookDeliveryCancellationServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-04T00:00:00Z");
    private final WebhookDeliveryRepository deliveries = mock(WebhookDeliveryRepository.class);

    @Test
    void consumesEveryBoundedPageUsingDomainCancellationWithoutInventingAttempts() {
        var first = WebhookDelivery.create("wdl_first", 1, 2, NOW);
        var second = WebhookDelivery.create("wdl_second", 1, 2, NOW);
        when(deliveries.findScheduledByEndpointForUpdate(2, 1)).thenReturn(List.of(first), List.of(second), List.of());
        service().cancelScheduled(2);
        assertThat(first.status()).isEqualTo(WebhookDeliveryStatus.DEAD);
        assertThat(second.status()).isEqualTo(WebhookDeliveryStatus.DEAD);
        assertThat(first.attemptCount()).isZero();
        assertThat(second.attemptCount()).isZero();
        assertThat(first.updatedAt()).isEqualTo(NOW);
        verify(deliveries).save(first);
        verify(deliveries).save(second);
        verify(deliveries, times(3)).findScheduledByEndpointForUpdate(2, 1);
    }

    @Test
    void alreadyCancelledEndpointHasNoDeliveryWrites() {
        when(deliveries.findScheduledByEndpointForUpdate(2, 1)).thenReturn(List.of());
        service().cancelScheduled(2);
        verify(deliveries, never()).save(any());
    }

    private WebhookDeliveryCancellationService service() {
        return new WebhookDeliveryCancellationService(deliveries,
                new WebhookDeliveryWorkerProperties(true, Duration.ofSeconds(1), 1, Duration.ofSeconds(30)),
                Clock.fixed(NOW.minusSeconds(1), ZoneOffset.UTC));
    }
}
