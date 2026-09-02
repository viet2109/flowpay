package com.flowpay.backend.infrastructure.messaging.outbox;

import com.flowpay.backend.infrastructure.messaging.outbox.relay.OutboxRelaySnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.DateTimeException;
import java.time.Instant;
import java.util.Objects;

@Component
@RequiredArgsConstructor
public class IntegrationEventEnvelopeMapper {

    private final ObjectMapper objectMapper;

    public IntegrationEventEnvelope from(OutboxEvent event) {
        OutboxEvent source = Objects.requireNonNull(event, "event must not be null");
        return from(
                source.eventId(),
                source.eventType(),
                source.aggregateType(),
                source.aggregateId(),
                source.occurredAt(),
                source.payload()
        );
    }

    public IntegrationEventEnvelope from(OutboxRelaySnapshot snapshot) {
        OutboxRelaySnapshot source = Objects.requireNonNull(
                snapshot,
                "snapshot must not be null"
        );
        return from(
                source.eventId(),
                source.eventType(),
                source.aggregateType(),
                source.aggregateId(),
                source.occurredAt(),
                source.payload()
        );
    }

    private IntegrationEventEnvelope from(
            String eventId,
            String eventType,
            String aggregateType,
            String aggregateId,
            Instant occurredAt,
            String serializedPayload
    ) {
        JsonNode payload = readPayload(serializedPayload);
        validateOccurrenceTime(occurredAt, payload);
        return new IntegrationEventEnvelope(
                eventId,
                eventType,
                aggregateType,
                aggregateId,
                occurredAt,
                payload
        );
    }

    private JsonNode readPayload(String payload) {
        try {
            JsonNode parsed = objectMapper.readTree(payload);
            if (parsed == null || !parsed.isObject()) {
                throw new IllegalStateException("Outbox payload must be a JSON object");
            }
            return parsed;
        } catch (JacksonException exception) {
            throw new IllegalStateException("Outbox payload is not valid JSON", exception);
        }
    }

    private static void validateOccurrenceTime(Instant occurredAt, JsonNode payload) {
        JsonNode payloadOccurredAt = payload.path("occurredAt");
        if (!payloadOccurredAt.isString()) {
            throw new IllegalStateException("Outbox payload must contain occurredAt");
        }
        try {
            if (!occurredAt.equals(Instant.parse(payloadOccurredAt.stringValue()))) {
                throw new IllegalStateException(
                        "Outbox envelope and payload occurredAt must match"
                );
            }
        } catch (DateTimeException exception) {
            throw new IllegalStateException("Outbox payload occurredAt is invalid", exception);
        }
    }
}
