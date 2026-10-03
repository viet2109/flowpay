package com.flowpay.backend.webhook.application;

import com.flowpay.backend.common.money.Money;
import com.flowpay.backend.webhook.domain.*;
import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static com.flowpay.backend.webhook.application.WebhookMaterializationResult.Outcome.*;

class WebhookEventMaterializationServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-03T01:02:03.123456Z");
    private final WebhookEventRepository events = mock(WebhookEventRepository.class);
    private final WebhookEndpointRepository endpoints = mock(WebhookEndpointRepository.class);
    private final WebhookDeliveryRepository deliveries = mock(WebhookDeliveryRepository.class);
    private final WebhookEventPublicIdGenerator eventIds = mock(WebhookEventPublicIdGenerator.class);
    private final WebhookDeliveryPublicIdGenerator deliveryIds = mock(WebhookDeliveryPublicIdGenerator.class);
    private final WebhookPublicPayloadCodec payloads = mock(WebhookPublicPayloadCodec.class);
    private final WebhookEventMaterializationService service = new WebhookEventMaterializationService(
            events, endpoints, deliveries, eventIds, deliveryIds, payloads, Clock.fixed(NOW, ZoneOffset.UTC));
    private final MaterializeWebhookEventCommand command = new MaterializeWebhookEventCommand("ievt_source", 7,
            WebhookEventType.PAYMENT_SUCCEEDED, "pi_source", null, Money.of(10000, "VND"), null, null, NOW);

    @Test
    void createsPendingDeliveriesOnlyAfterEventAcquisitionAndLockedSubscriptionSnapshot() {
        when(eventIds.nextId()).thenReturn("evt_new");
        when(deliveryIds.nextId()).thenReturn("wdl_one", "wdl_two");
        when(payloads.encode("evt_new", command)).thenReturn("{}");
        when(events.tryInsert(any())).thenReturn(Optional.of(existing("evt_new")));
        when(endpoints.findActiveSubscribedIdsForShare(7, command.eventType())).thenReturn(List.of(11L, 12L));
        assertThat(service.materialize(command)).isEqualTo(new WebhookMaterializationResult(MATERIALIZED, "evt_new", 2));
        var order = inOrder(events, endpoints, deliveries);
        order.verify(events).tryInsert(any());
        order.verify(endpoints).findActiveSubscribedIdsForShare(7, command.eventType());
        order.verify(deliveries).save(argThat(delivery -> delivery.publicId().equals("wdl_one")
                && delivery.webhookEventId() == 5 && delivery.webhookEndpointId() == 11
                && delivery.status() == WebhookDeliveryStatus.PENDING && delivery.attemptCount() == 0
                && delivery.nextAttemptAt().equals(NOW) && delivery.leaseExpiresAt() == null));
        order.verify(deliveries).save(argThat(delivery -> delivery.webhookEndpointId() == 12));
    }

    @Test
    void equivalentDuplicateNeverRecomputesSubscriptionsOrGeneratesDeliveryIdentity() {
        conflict();
        when(payloads.equivalent("{}", "evt_existing", command)).thenReturn(true);
        assertThat(service.materialize(command)).isEqualTo(new WebhookMaterializationResult(ALREADY_MATERIALIZED, "evt_existing", 0));
        verifyNoInteractions(endpoints, deliveries, deliveryIds);
    }

    @Test
    void contradictoryDuplicateNeverCreatesDeliveries() {
        conflict();
        when(payloads.equivalent("{}", "evt_existing", command)).thenReturn(false);
        assertThatThrownBy(() -> service.materialize(command)).isInstanceOf(ContradictoryWebhookSourceEventException.class);
        verifyNoInteractions(endpoints, deliveries, deliveryIds);
    }

    @Test
    void persistsAnEventWithoutMatchingEndpoints() {
        when(eventIds.nextId()).thenReturn("evt_new");
        when(payloads.encode("evt_new", command)).thenReturn("{}");
        when(events.tryInsert(any())).thenReturn(Optional.of(existing("evt_new")));
        assertThat(service.materialize(command)).isEqualTo(new WebhookMaterializationResult(MATERIALIZED, "evt_new", 0));
        verifyNoInteractions(deliveries, deliveryIds);
    }

    @Test
    void rejectsInconsistentCommandsAndPreservesUnicodeWhileBoundingFailureFacts() {
        assertThatThrownBy(() -> new MaterializeWebhookEventCommand("ievt_source", 7, command.eventType(),
                "pi_source", null, Money.of(0, "VND"), null, null, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MaterializeWebhookEventCommand("ievt_source", 7, command.eventType(),
                "re_wrong", null, command.amount(), null, null, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MaterializeWebhookEventCommand("ievt_source", 7, command.eventType(),
                "pi_source", null, command.amount(), "DECLINED", "Failure", NOW)).isInstanceOf(IllegalArgumentException.class);
        var failed = new MaterializeWebhookEventCommand("ievt_source", 7, WebhookEventType.REFUND_FAILED,
                "re_source", "pi_source", command.amount(), " CODE ", "\uD83D\uDE00".repeat(256), NOW);
        assertThat(failed.failureCode()).isEqualTo("CODE");
        assertThat(failed.failureMessage()).isEqualTo("\uD83D\uDE00".repeat(255));
    }

    private void conflict() {
        when(eventIds.nextId()).thenReturn("evt_unused");
        when(payloads.encode("evt_unused", command)).thenReturn("{}");
        when(events.tryInsert(any())).thenReturn(Optional.empty());
        when(events.findBySourceEventId(command.sourceEventId())).thenReturn(Optional.of(existing("evt_existing")));
    }

    private WebhookEvent existing(String id) {
        return WebhookEvent.rehydrate(5, id, command.sourceEventId(), command.merchantId(), command.eventType(),
                WebhookResourceType.PAYMENT_INTENT, command.resourceId(), "{}", NOW, NOW);
    }
}
