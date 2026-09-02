package com.flowpay.backend.infrastructure.messaging.rabbit;

import com.flowpay.backend.infrastructure.messaging.outbox.IntegrationEventEnvelope;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IntegrationEventEnvelopeParserTest {

    private static final String EVENT_ID = "ievt_parser";
    private static final String EVENT_TYPE = "payment.succeeded.v1";
    private static final String VALID_ENVELOPE = """
            {
              "eventId": "ievt_parser",
              "eventType": "payment.succeeded.v1",
              "aggregateType": "PAYMENT_INTENT",
              "aggregateId": "pi_parser",
              "occurredAt": "2026-09-02T08:00:00Z",
              "payload": {
                "merchantInternalId": 15,
                "paymentPublicId": "pi_parser",
                "amountMinor": 1000,
                "currency": "VND",
                "occurredAt": "2026-09-02T08:00:00Z"
              }
            }
            """;

    private final IntegrationEventEnvelopeParser parser =
            new IntegrationEventEnvelopeParser(
                    JsonMapper.builder().findAndAddModules().build()
            );

    @Test
    void shouldParseExplicitEnvelopeAndValidateTransportMetadata() {
        IntegrationEventEnvelope envelope = parser.parse(message(
                VALID_ENVELOPE,
                EVENT_ID,
                EVENT_TYPE,
                MessageProperties.CONTENT_TYPE_JSON
        ));

        assertThat(envelope.eventId()).isEqualTo(EVENT_ID);
        assertThat(envelope.eventType()).isEqualTo(EVENT_TYPE);
        assertThat(envelope.aggregateType()).isEqualTo("PAYMENT_INTENT");
        assertThat(envelope.aggregateId()).isEqualTo("pi_parser");
        assertThat(envelope.occurredAt())
                .isEqualTo(Instant.parse("2026-09-02T08:00:00Z"));
    }

    @Test
    void shouldRejectMalformedOrMissingEnvelopeFields() {
        assertInvalid(message(
                "{not-json}",
                EVENT_ID,
                EVENT_TYPE,
                MessageProperties.CONTENT_TYPE_JSON
        ));
        assertInvalid(message(
                """
                        {
                          "eventType": "payment.succeeded.v1",
                          "aggregateType": "PAYMENT_INTENT",
                          "aggregateId": "pi_missing",
                          "occurredAt": "2026-09-02T08:00:00Z",
                          "payload": {}
                        }
                        """,
                EVENT_ID,
                EVENT_TYPE,
                MessageProperties.CONTENT_TYPE_JSON
        ));
    }

    @Test
    void shouldRejectTransportMetadataMismatch() {
        assertInvalid(message(
                VALID_ENVELOPE,
                "ievt_different",
                EVENT_TYPE,
                MessageProperties.CONTENT_TYPE_JSON
        ));
        assertInvalid(message(
                VALID_ENVELOPE,
                EVENT_ID,
                "refund.succeeded.v1",
                MessageProperties.CONTENT_TYPE_JSON
        ));
        assertInvalid(message(
                VALID_ENVELOPE,
                EVENT_ID,
                EVENT_TYPE,
                MessageProperties.CONTENT_TYPE_TEXT_PLAIN
        ));
        assertInvalid(message(
                VALID_ENVELOPE,
                " ",
                EVENT_TYPE,
                MessageProperties.CONTENT_TYPE_JSON
        ));
    }

    private void assertInvalid(Message message) {
        assertThatThrownBy(() -> parser.parse(message))
                .isInstanceOf(InvalidIntegrationEventException.class);
    }

    private static Message message(
            String body,
            String messageId,
            String routingKey,
            String contentType
    ) {
        MessageProperties properties = new MessageProperties();
        properties.setMessageId(messageId);
        properties.setReceivedRoutingKey(routingKey);
        properties.setContentType(contentType);
        properties.setContentEncoding(StandardCharsets.UTF_8.name());
        return new Message(body.getBytes(StandardCharsets.UTF_8), properties);
    }
}
