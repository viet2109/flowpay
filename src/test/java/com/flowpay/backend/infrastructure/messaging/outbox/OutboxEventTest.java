package com.flowpay.backend.infrastructure.messaging.outbox;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutboxEventTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-09-01T11:00:00Z");
    private static final Instant CREATED_AT = OCCURRED_AT.plusSeconds(1);
    private static final String PAYLOAD = """
            {"paymentPublicId":"pi_contract","occurredAt":"2026-09-01T11:00:00Z"}
            """;

    @Test
    void shouldCreateInitialPendingEvent() {
        OutboxEvent event = pendingEvent();

        assertThat(event.internalId()).isNull();
        assertThat(event.status()).isEqualTo(OutboxStatus.PENDING);
        assertThat(event.availableAt()).isEqualTo(CREATED_AT);
        assertThat(event.createdAt()).isEqualTo(CREATED_AT);
        assertThat(event.publishedAt()).isNull();
        assertThat(event.retryCount()).isZero();
        assertThat(event.lastError()).isNull();
    }

    @Test
    void shouldRejectInvalidIdentityAndLifecycleState() {
        assertThatThrownBy(() -> OutboxEvent.pending(
                "evt_wrong_prefix",
                "PAYMENT_INTENT",
                "pi_contract",
                "payment.succeeded.v1",
                PAYLOAD,
                OCCURRED_AT,
                CREATED_AT
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ievt_");

        assertThatThrownBy(() -> new OutboxEvent(
                1L,
                "ievt_invalid_failed",
                "PAYMENT_INTENT",
                "pi_contract",
                "payment.succeeded.v1",
                PAYLOAD,
                OutboxStatus.FAILED,
                OCCURRED_AT,
                CREATED_AT,
                null,
                0,
                null,
                CREATED_AT
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("retry metadata");
    }

    @Test
    void envelopeShouldDefensivelyCopyJsonPayload() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        ObjectNode payload = (ObjectNode) objectMapper.readTree(PAYLOAD);
        IntegrationEventEnvelope envelope = new IntegrationEventEnvelope(
                "ievt_envelope",
                "payment.succeeded.v1",
                "PAYMENT_INTENT",
                "pi_contract",
                OCCURRED_AT,
                payload
        );

        payload.put("paymentPublicId", "changed_before_read");
        ObjectNode returned = (ObjectNode) envelope.payload();
        returned.put("paymentPublicId", "changed_after_read");

        assertThat(envelope.payload().path("paymentPublicId").stringValue())
                .isEqualTo("pi_contract");
    }

    private static OutboxEvent pendingEvent() {
        return OutboxEvent.pending(
                "ievt_pending",
                "PAYMENT_INTENT",
                "pi_contract",
                "payment.succeeded.v1",
                PAYLOAD,
                OCCURRED_AT,
                CREATED_AT
        );
    }
}
