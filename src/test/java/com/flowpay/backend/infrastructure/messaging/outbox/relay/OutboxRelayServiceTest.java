package com.flowpay.backend.infrastructure.messaging.outbox.relay;

import com.flowpay.backend.infrastructure.messaging.FlowPayMessagingProperties;
import com.flowpay.backend.infrastructure.messaging.IntegrationEventPublicationResult;
import com.flowpay.backend.infrastructure.messaging.IntegrationEventPublicationStatus;
import com.flowpay.backend.infrastructure.messaging.IntegrationEventTransportPublisher;
import com.flowpay.backend.infrastructure.messaging.outbox.IntegrationEventEnvelope;
import com.flowpay.backend.infrastructure.messaging.outbox.IntegrationEventEnvelopeMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxRelayServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-02T06:00:00Z");

    private final OutboxRelayPersistenceService persistenceService =
            mock(OutboxRelayPersistenceService.class);
    private final IntegrationEventEnvelopeMapper envelopeMapper =
            mock(IntegrationEventEnvelopeMapper.class);
    private final IntegrationEventTransportPublisher transportPublisher =
            mock(IntegrationEventTransportPublisher.class);
    private final FlowPayMessagingProperties properties = properties();
    private final OutboxRelayService relayService = new OutboxRelayService(
            persistenceService,
            envelopeMapper,
            transportPublisher,
            new OutboxRelayBackoffPolicy(properties),
            new OutboxRelayFailureSummary(),
            properties,
            Clock.fixed(NOW, ZoneOffset.UTC)
    );

    @Test
    void shouldReturnEmptyBatchWithoutPublishing() {
        when(persistenceService.findDue(NOW, 2)).thenReturn(List.of());

        assertThat(relayService.relayDueEvents())
                .isEqualTo(OutboxRelayBatchResult.empty());
    }

    @Test
    void shouldIsolateUnexpectedFailureAndContinueWithLaterEvent() {
        OutboxRelaySnapshot unsafe = snapshot("ievt_unsafe", 2);
        OutboxRelaySnapshot succeeding = snapshot("ievt_succeeding", 0);
        IntegrationEventEnvelope envelope = mock(IntegrationEventEnvelope.class);
        when(persistenceService.findDue(NOW, 2))
                .thenReturn(List.of(unsafe, succeeding));
        when(envelopeMapper.from(unsafe)).thenThrow(new IllegalStateException(
                "amqp://user:password@broker\nAuthorization: secret"
        ));
        when(envelopeMapper.from(succeeding)).thenReturn(envelope);
        when(transportPublisher.publish(envelope)).thenReturn(
                IntegrationEventPublicationResult.of(
                        IntegrationEventPublicationStatus.CONFIRMED
                )
        );

        OutboxRelayBatchResult result = relayService.relayDueEvents();

        assertThat(result).isEqualTo(new OutboxRelayBatchResult(2, 1, 1));
        verify(persistenceService).recordFailure(
                "ievt_unsafe",
                NOW.plusSeconds(4),
                "Outbox publication failed unexpectedly: IllegalStateException"
        );
        verify(persistenceService).markPublished("ievt_succeeding", NOW);
    }

    private static OutboxRelaySnapshot snapshot(String eventId, int retryCount) {
        return new OutboxRelaySnapshot(
                eventId,
                "PAYMENT_INTENT",
                "pi_test",
                "payment.succeeded.v1",
                "{\"occurredAt\":\"2026-09-02T05:00:00Z\"}",
                Instant.parse("2026-09-02T05:00:00Z"),
                retryCount
        );
    }

    private static FlowPayMessagingProperties properties() {
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
                                2,
                                Duration.ofSeconds(1),
                                Duration.ofSeconds(4)
                        )
                )
        );
    }
}
