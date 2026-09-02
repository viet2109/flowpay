package com.flowpay.backend.infrastructure.messaging.rabbit;

import com.flowpay.backend.infrastructure.messaging.outbox.IntegrationEventEnvelope;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.Objects;

@Component
@RequiredArgsConstructor
public class IntegrationEventEnvelopeParser {

    private static final String INVALID_ENVELOPE_MESSAGE =
            "Integration event envelope is invalid";

    private final ObjectMapper objectMapper;

    public IntegrationEventEnvelope parse(Message message) {
        Message source = Objects.requireNonNull(message, "message must not be null");
        MessageProperties metadata = source.getMessageProperties();
        requireJsonContentType(metadata.getContentType());
        String messageId = requireText(metadata.getMessageId(), "messageId");

        IntegrationEventEnvelope envelope;
        try {
            envelope = objectMapper.readValue(
                    source.getBody(),
                    IntegrationEventEnvelope.class
            );
        } catch (JacksonException | IllegalArgumentException exception) {
            throw new InvalidIntegrationEventException(
                    INVALID_ENVELOPE_MESSAGE,
                    exception
            );
        }

        if (!messageId.equals(envelope.eventId())) {
            throw new InvalidIntegrationEventException(
                    "RabbitMQ messageId must match envelope eventId"
            );
        }
        String routingKey = metadata.getReceivedRoutingKey();
        if (hasText(routingKey) && !routingKey.equals(envelope.eventType())) {
            throw new InvalidIntegrationEventException(
                    "RabbitMQ routing key must match envelope eventType"
            );
        }
        return envelope;
    }

    private static void requireJsonContentType(String contentType) {
        if (!MessageProperties.CONTENT_TYPE_JSON.equalsIgnoreCase(contentType)) {
            throw new InvalidIntegrationEventException(
                    "RabbitMQ message contentType must be application/json"
            );
        }
    }

    private static String requireText(String value, String fieldName) {
        if (!hasText(value)) {
            throw new InvalidIntegrationEventException(
                    "RabbitMQ " + fieldName + " must not be blank"
            );
        }
        return value.trim();
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
