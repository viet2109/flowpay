package com.flowpay.backend.infrastructure.messaging.rabbit;

import com.flowpay.backend.infrastructure.messaging.outbox.IntegrationEventEnvelope;
import com.flowpay.backend.webhook.application.*;
import com.flowpay.backend.webhook.domain.*;
import com.flowpay.backend.webhook.infrastructure.JacksonWebhookPublicPayloadCodec;
import com.flowpay.backend.webhook.infrastructure.WebhookPublicPayloadMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mapstruct.factory.Mappers;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WebhookIntegrationEventDispatcherTest {
    private static final Instant OCCURRED = Instant.parse("2026-10-03T01:02:03.123456789Z");
    private final ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();
    private final WebhookEventMaterializationService materializer = mock(WebhookEventMaterializationService.class);
    private final WebhookIntegrationEventDispatcher dispatcher = new WebhookIntegrationEventDispatcher(mapper, materializer);
    private final WebhookPublicPayloadCodec codec = new JacksonWebhookPublicPayloadCodec(mapper,
            Mappers.getMapper(WebhookPublicPayloadMapper.class));

    @ParameterizedTest
    @EnumSource(WebhookEventType.class)
    void mapsAllSixToExactPublicBodiesUsingOnlySourceFacts(WebhookEventType type) {
        var envelope = envelope(type);
        var command = dispatcher.command(envelope);
        dispatcher.dispatch(envelope);
        verify(materializer).materialize(command);
        boolean refund = WebhookResourceType.forEventType(type) == WebhookResourceType.REFUND;
        boolean failed = type == WebhookEventType.PAYMENT_FAILED || type == WebhookEventType.REFUND_FAILED;
        String status = type.name().substring(type.name().indexOf('_') + 1);
        String expected = """
                {"id":"evt_public","type":"%s","createdAt":"%s","data":{"%s":{
                "id":"%s",%s"amount":100000,"currency":"VND","status":"%s",
                "failureCode":%s,"failureMessage":%s}}}
                """.formatted(type.value(), OCCURRED, refund ? "refund" : "payment",
                refund ? "re_source" : "pi_source", refund ? "\"paymentId\":\"pi_source\"," : "",
                status, failed ? "\"DECLINED\"" : "null", failed ? "\"Payment declined\"" : "null");
        String body = codec.encode("evt_public", command);
        assertThat(mapper.readTree(body)).isEqualTo(mapper.readTree(expected));
        assertThat(body).doesNotContain("merchantInternalId", "merchantId", "orderId", "ievt_", ".v1");
        assertThat(command.merchantId()).isEqualTo(7);
        assertThat(command.occurredAt()).isEqualTo(OCCURRED);
        assertThat(codec.equivalent(expected, "evt_public", command)).isTrue();
    }

    @Test
    void failureFieldsAreTrimmedAndBoundedBeforeSnapshotCreation() {
        var envelope = envelope(WebhookEventType.REFUND_FAILED);
        var payload = (tools.jackson.databind.node.ObjectNode) envelope.payload();
        payload.put("failureCode", "  " + "C".repeat(80) + "  ");
        payload.put("failureMessage", "  " + "M".repeat(300) + "  ");
        var command = dispatcher.command(copy(envelope, payload, envelope.aggregateType(), envelope.aggregateId(), OCCURRED));
        assertThat(command.failureCode()).isEqualTo("C".repeat(64));
        assertThat(command.failureMessage()).isEqualTo("M".repeat(255));
    }

    @Test
    void rejectsMalformedAndMismatchedFactsBeforeInvokingMaterializer() {
        var valid = envelope(WebhookEventType.PAYMENT_FAILED);
        assertInvalid(copy(valid, valid.payload(), "REFUND", valid.aggregateId(), OCCURRED));
        assertInvalid(copy(valid, valid.payload(), valid.aggregateType(), "pi_other", OCCURRED));
        assertInvalid(copy(valid, valid.payload(), valid.aggregateType(), valid.aggregateId(), OCCURRED.plusSeconds(1)));
        for (String field : new String[]{"merchantInternalId", "amountMinor", "currency", "paymentPublicId",
                "failureCode", "failureMessage", "occurredAt"}) {
            var missing = (tools.jackson.databind.node.ObjectNode) valid.payload();
            missing.remove(field);
            assertInvalid(copy(valid, missing, valid.aggregateType(), valid.aggregateId(), OCCURRED));
        }
        var malformed = (tools.jackson.databind.node.ObjectNode) valid.payload();
        malformed.put("amountMinor", "100000");
        assertInvalid(copy(valid, malformed, valid.aggregateType(), valid.aggregateId(), OCCURRED));
        malformed.put("amountMinor", 1.1);
        assertInvalid(copy(valid, malformed, valid.aggregateType(), valid.aggregateId(), OCCURRED));
        malformed.put("amountMinor", -1);
        assertInvalid(copy(valid, malformed, valid.aggregateType(), valid.aggregateId(), OCCURRED));
        verifyNoInteractions(materializer);
    }

    @Test
    void rejectsUnsupportedVersionAndMissingRefundPaymentIdentity() {
        var valid = envelope(WebhookEventType.REFUND_SUCCEEDED);
        assertInvalid(new IntegrationEventEnvelope(valid.eventId(), "refund.succeeded.v2", valid.aggregateType(),
                valid.aggregateId(), OCCURRED, valid.payload()));
        var missing = (tools.jackson.databind.node.ObjectNode) valid.payload();
        missing.remove("paymentPublicId");
        assertInvalid(copy(valid, missing, valid.aggregateType(), valid.aggregateId(), OCCURRED));
        verifyNoInteractions(materializer);
    }

    private IntegrationEventEnvelope envelope(WebhookEventType type) {
        boolean refund = WebhookResourceType.forEventType(type) == WebhookResourceType.REFUND;
        var payload = mapper.createObjectNode();
        payload.put("merchantInternalId", 7);
        payload.put("paymentPublicId", "pi_source");
        if (refund) payload.put("refundPublicId", "re_source");
        payload.put("amountMinor", 100000);
        payload.put("currency", " vnd ");
        payload.put("occurredAt", OCCURRED.toString());
        if (type == WebhookEventType.PAYMENT_FAILED || type == WebhookEventType.REFUND_FAILED) {
            payload.put("failureCode", " DECLINED ");
            payload.put("failureMessage", " Payment declined ");
        }
        return new IntegrationEventEnvelope("ievt_source", type.value() + ".v1",
                refund ? "REFUND" : "PAYMENT_INTENT", refund ? "re_source" : "pi_source", OCCURRED, payload);
    }

    private IntegrationEventEnvelope copy(IntegrationEventEnvelope original, tools.jackson.databind.JsonNode payload,
            String aggregateType, String aggregateId, Instant occurredAt) {
        return new IntegrationEventEnvelope(original.eventId(), original.eventType(), aggregateType,
                aggregateId, occurredAt, payload);
    }

    private void assertInvalid(IntegrationEventEnvelope envelope) {
        assertThatThrownBy(() -> dispatcher.dispatch(envelope)).isInstanceOf(InvalidIntegrationEventException.class);
    }
}
