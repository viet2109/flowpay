package com.flowpay.backend.infrastructure.messaging.rabbit;

import com.flowpay.backend.infrastructure.messaging.FlowPayMessagingProperties;
import com.flowpay.backend.infrastructure.messaging.IntegrationEventPublicationResult;
import com.flowpay.backend.infrastructure.messaging.IntegrationEventPublicationStatus;
import com.flowpay.backend.infrastructure.messaging.IntegrationEventTransportPublisher;
import com.flowpay.backend.infrastructure.messaging.outbox.IntegrationEventEnvelope;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
@RequiredArgsConstructor
public class ConfirmedRabbitIntegrationEventPublisher
        implements IntegrationEventTransportPublisher {

    static final String EVENT_TYPE_HEADER = "x-flowpay-event-type";
    static final String AGGREGATE_TYPE_HEADER = "x-flowpay-aggregate-type";
    static final String AGGREGATE_ID_HEADER = "x-flowpay-aggregate-id";

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final FlowPayMessagingProperties properties;

    @Override
    public IntegrationEventPublicationResult publish(IntegrationEventEnvelope envelope) {
        IntegrationEventEnvelope event = Objects.requireNonNull(
                envelope,
                "envelope must not be null"
        );
        Message message = toMessage(event);
        CorrelationData correlation = new CorrelationData(event.eventId());
        try {
            rabbitTemplate.send(
                    properties.topology().exchange(),
                    event.eventType(),
                    message,
                    correlation
            );
            CorrelationData.Confirm confirm = awaitConfirm(correlation);
            if (correlation.getReturned() != null) {
                return result(IntegrationEventPublicationStatus.UNROUTABLE);
            }
            if (!confirm.ack()) {
                return result(IntegrationEventPublicationStatus.NEGATIVE_ACK);
            }
            return result(IntegrationEventPublicationStatus.CONFIRMED);
        } catch (TimeoutException exception) {
            return result(IntegrationEventPublicationStatus.CONFIRM_TIMEOUT);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return result(IntegrationEventPublicationStatus.TRANSPORT_FAILURE);
        } catch (ExecutionException | CancellationException | AmqpException exception) {
            return result(IntegrationEventPublicationStatus.TRANSPORT_FAILURE);
        }
    }

    private CorrelationData.Confirm awaitConfirm(CorrelationData correlation)
            throws InterruptedException, ExecutionException, TimeoutException {
        Duration timeout = properties.outbox().publisherConfirmTimeout();
        return correlation.getFuture().get(timeout.toNanos(), TimeUnit.NANOSECONDS);
    }

    private Message toMessage(IntegrationEventEnvelope envelope) {
        MessageProperties messageProperties = new MessageProperties();
        messageProperties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        messageProperties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        messageProperties.setContentEncoding(StandardCharsets.UTF_8.name());
        messageProperties.setMessageId(envelope.eventId());
        messageProperties.setHeader(EVENT_TYPE_HEADER, envelope.eventType());
        messageProperties.setHeader(AGGREGATE_TYPE_HEADER, envelope.aggregateType());
        messageProperties.setHeader(AGGREGATE_ID_HEADER, envelope.aggregateId());
        try {
            return new Message(
                    objectMapper.writeValueAsBytes(envelope),
                    messageProperties
            );
        } catch (JacksonException exception) {
            throw new IllegalStateException(
                    "Integration event envelope could not be serialized",
                    exception
            );
        }
    }

    private static IntegrationEventPublicationResult result(
            IntegrationEventPublicationStatus status
    ) {
        return IntegrationEventPublicationResult.of(status);
    }
}
