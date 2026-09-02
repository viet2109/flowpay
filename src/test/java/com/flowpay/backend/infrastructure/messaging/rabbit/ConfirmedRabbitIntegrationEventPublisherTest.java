package com.flowpay.backend.infrastructure.messaging.rabbit;

import com.flowpay.backend.infrastructure.messaging.FlowPayMessagingProperties;
import com.flowpay.backend.infrastructure.messaging.IntegrationEventPublicationResult;
import com.flowpay.backend.infrastructure.messaging.IntegrationEventPublicationStatus;
import com.flowpay.backend.infrastructure.messaging.outbox.IntegrationEventEnvelope;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConfirmedRabbitIntegrationEventPublisherTest {

    private final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
    private final ObjectMapper objectMapper = mock(ObjectMapper.class);

    @Test
    void shouldPublishPersistentJsonAndReturnConfirmedAfterPositiveAck() throws Exception {
        IntegrationEventEnvelope envelope = envelope("ievt_confirmed", "payment.succeeded.v1");
        byte[] serialized = "{\"eventId\":\"ievt_confirmed\"}"
                .getBytes(StandardCharsets.UTF_8);
        when(objectMapper.writeValueAsBytes(envelope)).thenReturn(serialized);
        AtomicReference<Message> sentMessage = new AtomicReference<>();
        AtomicReference<CorrelationData> sentCorrelation = new AtomicReference<>();
        doAnswer(invocation -> {
            sentMessage.set(invocation.getArgument(2));
            CorrelationData correlation = invocation.getArgument(3);
            sentCorrelation.set(correlation);
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).send(
                anyString(), anyString(), any(Message.class), any(CorrelationData.class)
        );

        IntegrationEventPublicationResult result = publisher(Duration.ofSeconds(1))
                .publish(envelope);

        assertThat(result.status()).isEqualTo(IntegrationEventPublicationStatus.CONFIRMED);
        assertThat(result.confirmed()).isTrue();
        assertThat(sentCorrelation.get().getId()).isEqualTo(envelope.eventId());
        assertThat(sentMessage.get().getBody()).isEqualTo(serialized);
        assertThat(sentMessage.get().getMessageProperties().getDeliveryMode())
                .isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(sentMessage.get().getMessageProperties().getContentType())
                .isEqualTo("application/json");
        assertThat(sentMessage.get().getMessageProperties().getMessageId())
                .isEqualTo(envelope.eventId());
        assertThat((String) sentMessage.get().getMessageProperties().getHeader(
                ConfirmedRabbitIntegrationEventPublisher.EVENT_TYPE_HEADER
        )).isEqualTo(envelope.eventType());
        assertThat((String) sentMessage.get().getMessageProperties().getHeader(
                ConfirmedRabbitIntegrationEventPublisher.AGGREGATE_TYPE_HEADER
        )).isEqualTo(envelope.aggregateType());
        assertThat((String) sentMessage.get().getMessageProperties().getHeader(
                ConfirmedRabbitIntegrationEventPublisher.AGGREGATE_ID_HEADER
        )).isEqualTo(envelope.aggregateId());
        verify(rabbitTemplate).send(
                "flowpay.events",
                envelope.eventType(),
                sentMessage.get(),
                sentCorrelation.get()
        );
    }

    @Test
    void shouldTreatNegativeAckAsFailure() throws Exception {
        IntegrationEventEnvelope envelope = envelope("ievt_nack", "payment.succeeded.v1");
        when(objectMapper.writeValueAsBytes(envelope))
                .thenReturn("{}".getBytes(StandardCharsets.UTF_8));
        completeConfirm(false, false);

        IntegrationEventPublicationResult result = publisher(Duration.ofSeconds(1))
                .publish(envelope);

        assertThat(result.status())
                .isEqualTo(IntegrationEventPublicationStatus.NEGATIVE_ACK);
        assertThat(result.confirmed()).isFalse();
    }

    @Test
    void shouldTreatReturnedMessageAsUnroutableEvenWithPositiveAck() throws Exception {
        IntegrationEventEnvelope envelope = envelope("ievt_returned", "unknown.event.v1");
        when(objectMapper.writeValueAsBytes(envelope))
                .thenReturn("{}".getBytes(StandardCharsets.UTF_8));
        completeConfirm(true, true);

        IntegrationEventPublicationResult result = publisher(Duration.ofSeconds(1))
                .publish(envelope);

        assertThat(result.status()).isEqualTo(IntegrationEventPublicationStatus.UNROUTABLE);
        assertThat(result.confirmed()).isFalse();
    }

    @Test
    void shouldReturnTimeoutWhenBrokerConfirmDoesNotArrive() throws Exception {
        IntegrationEventEnvelope envelope = envelope("ievt_timeout", "payment.succeeded.v1");
        when(objectMapper.writeValueAsBytes(envelope))
                .thenReturn("{}".getBytes(StandardCharsets.UTF_8));

        IntegrationEventPublicationResult result = publisher(Duration.ofMillis(5))
                .publish(envelope);

        assertThat(result.status())
                .isEqualTo(IntegrationEventPublicationStatus.CONFIRM_TIMEOUT);
    }

    @Test
    void shouldReturnTransportFailureWhenSendFails() throws Exception {
        IntegrationEventEnvelope envelope = envelope("ievt_failure", "payment.succeeded.v1");
        when(objectMapper.writeValueAsBytes(envelope))
                .thenReturn("{}".getBytes(StandardCharsets.UTF_8));
        doThrow(new AmqpException("broker unavailable"))
                .when(rabbitTemplate)
                .send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

        IntegrationEventPublicationResult result = publisher(Duration.ofSeconds(1))
                .publish(envelope);

        assertThat(result.status())
                .isEqualTo(IntegrationEventPublicationStatus.TRANSPORT_FAILURE);
    }

    private void completeConfirm(boolean ack, boolean returned) {
        doAnswer(invocation -> {
            Message message = invocation.getArgument(2);
            CorrelationData correlation = invocation.getArgument(3);
            if (returned) {
                correlation.setReturned(new ReturnedMessage(
                        message,
                        312,
                        "NO_ROUTE",
                        "flowpay.events",
                        "unknown.event.v1"
                ));
            }
            correlation.getFuture().complete(new CorrelationData.Confirm(
                    ack,
                    ack ? null : "broker nack"
            ));
            return null;
        }).when(rabbitTemplate).send(
                anyString(), anyString(), any(Message.class), any(CorrelationData.class)
        );
    }

    private ConfirmedRabbitIntegrationEventPublisher publisher(Duration timeout) {
        return new ConfirmedRabbitIntegrationEventPublisher(
                rabbitTemplate,
                objectMapper,
                properties(timeout)
        );
    }

    private static FlowPayMessagingProperties properties(Duration timeout) {
        return new FlowPayMessagingProperties(
                new FlowPayMessagingProperties.Topology(
                        "flowpay.events",
                        "flowpay.ledger.events",
                        "flowpay.events.dlx",
                        "flowpay.ledger.events.dlq",
                        "ledger.dead"
                ),
                new FlowPayMessagingProperties.Outbox(
                        timeout,
                        new FlowPayMessagingProperties.Relay(
                                false,
                                Duration.ofSeconds(1),
                                100,
                                Duration.ofSeconds(1),
                                Duration.ofMinutes(1)
                        )
                )
        );
    }

    private static IntegrationEventEnvelope envelope(String eventId, String eventType)
            throws Exception {
        return new IntegrationEventEnvelope(
                eventId,
                eventType,
                "PAYMENT_INTENT",
                "pi_test",
                Instant.parse("2026-09-02T05:00:00Z"),
                new ObjectMapper().readTree("{\"amountMinor\":1000}")
        );
    }
}
