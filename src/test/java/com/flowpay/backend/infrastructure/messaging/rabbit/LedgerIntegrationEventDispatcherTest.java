package com.flowpay.backend.infrastructure.messaging.rabbit;

import com.flowpay.backend.infrastructure.messaging.outbox.IntegrationEventEnvelope;
import com.flowpay.backend.ledger.application.LedgerPostingOutcome;
import com.flowpay.backend.ledger.application.LedgerPostingResult;
import com.flowpay.backend.ledger.application.PostPaymentSucceededCommand;
import com.flowpay.backend.ledger.application.PostRefundSucceededCommand;
import com.flowpay.backend.payment.application.event.PaymentSucceededEventV1;
import com.flowpay.backend.refund.application.event.RefundSucceededEventV1;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Currency;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LedgerIntegrationEventDispatcherTest {

    private static final Instant OCCURRED_AT =
            Instant.parse("2026-09-02T08:00:00Z");
    private static final Currency VND = Currency.getInstance("VND");

    private final ObjectMapper objectMapper =
            JsonMapper.builder().findAndAddModules().build();
    private final LedgerIntegrationEventHandler eventHandler =
            mock(LedgerIntegrationEventHandler.class);
    private final LedgerIntegrationEventDispatcher dispatcher =
            new LedgerIntegrationEventDispatcher(objectMapper, eventHandler);

    @Test
    void shouldMapPaymentEventToExactLedgerCommand() throws Exception {
        LedgerPostingResult expected = created("ltxn_payment_event");
        when(eventHandler.handlePayment(org.mockito.ArgumentMatchers.any()))
                .thenReturn(expected);

        LedgerPostingResult result = dispatcher.dispatch(paymentEnvelope(
                "PAYMENT_INTENT",
                "pi_event",
                OCCURRED_AT,
                paymentPayload("pi_event", 1_000_000L, "VND", OCCURRED_AT)
        ));

        assertThat(result).isEqualTo(expected);
        ArgumentCaptor<PostPaymentSucceededCommand> command =
                ArgumentCaptor.forClass(PostPaymentSucceededCommand.class);
        verify(eventHandler).handlePayment(command.capture());
        assertThat(command.getValue()).isEqualTo(new PostPaymentSucceededCommand(
                15L,
                "pi_event",
                new com.flowpay.backend.common.money.Money(1_000_000L, VND),
                OCCURRED_AT
        ));
    }

    @Test
    void shouldMapRefundEventToExactLedgerCommand() throws Exception {
        LedgerPostingResult expected = created("ltxn_refund_event");
        when(eventHandler.handleRefund(org.mockito.ArgumentMatchers.any()))
                .thenReturn(expected);

        LedgerPostingResult result = dispatcher.dispatch(refundEnvelope(
                "REFUND",
                "re_event",
                OCCURRED_AT,
                refundPayload("re_event", 300_000L, "VND", OCCURRED_AT)
        ));

        assertThat(result).isEqualTo(expected);
        ArgumentCaptor<PostRefundSucceededCommand> command =
                ArgumentCaptor.forClass(PostRefundSucceededCommand.class);
        verify(eventHandler).handleRefund(command.capture());
        assertThat(command.getValue()).isEqualTo(new PostRefundSucceededCommand(
                15L,
                "re_event",
                new com.flowpay.backend.common.money.Money(300_000L, VND),
                OCCURRED_AT
        ));
    }

    @Test
    void shouldTreatAlreadyPostedAsSuccessfulDispatch() throws Exception {
        LedgerPostingResult duplicate = new LedgerPostingResult(
                LedgerPostingOutcome.ALREADY_POSTED,
                "ltxn_existing"
        );
        when(eventHandler.handlePayment(org.mockito.ArgumentMatchers.any()))
                .thenReturn(duplicate);

        assertThat(dispatcher.dispatch(paymentEnvelope(
                "PAYMENT_INTENT",
                "pi_duplicate",
                OCCURRED_AT,
                paymentPayload("pi_duplicate", 1_000L, "VND", OCCURRED_AT)
        ))).isEqualTo(duplicate);
    }

    @Test
    void shouldRejectMalformedOrInconsistentEventsBeforeLedger() throws Exception {
        JsonNode validPayment = paymentPayload(
                "pi_valid",
                1_000L,
                "VND",
                OCCURRED_AT
        );
        List<IntegrationEventEnvelope> invalidEvents = List.of(
                paymentEnvelope("REFUND", "pi_valid", OCCURRED_AT, validPayment),
                paymentEnvelope("PAYMENT_INTENT", "pi_other", OCCURRED_AT, validPayment),
                paymentEnvelope(
                        "PAYMENT_INTENT",
                        "pi_valid",
                        OCCURRED_AT.plusSeconds(1),
                        validPayment
                ),
                paymentEnvelope(
                        "PAYMENT_INTENT",
                        "pi_invalid_amount",
                        OCCURRED_AT,
                        objectMapper.readTree("""
                                {
                                  "merchantInternalId": 15,
                                  "paymentPublicId": "pi_invalid_amount",
                                  "amountMinor": 0,
                                  "currency": "VND",
                                  "occurredAt": "2026-09-02T08:00:00Z"
                                }
                                """)
                ),
                paymentEnvelope(
                        "PAYMENT_INTENT",
                        "pi_invalid_currency",
                        OCCURRED_AT,
                        objectMapper.readTree("""
                                {
                                  "merchantInternalId": 15,
                                  "paymentPublicId": "pi_invalid_currency",
                                  "amountMinor": 1000,
                                  "currency": "NOT_A_CURRENCY",
                                  "occurredAt": "2026-09-02T08:00:00Z"
                                }
                                """)
                ),
                paymentEnvelope(
                        "PAYMENT_INTENT",
                        "pi_missing",
                        OCCURRED_AT,
                        objectMapper.readTree("""
                                {
                                  "merchantInternalId": 15,
                                  "amountMinor": 1000,
                                  "currency": "VND",
                                  "occurredAt": "2026-09-02T08:00:00Z"
                                }
                                """)
                ),
                new IntegrationEventEnvelope(
                        "ievt_unsupported",
                        "payment.succeeded.v2",
                        "PAYMENT_INTENT",
                        "pi_valid",
                        OCCURRED_AT,
                        validPayment
                )
        );

        for (IntegrationEventEnvelope invalid : invalidEvents) {
            assertThatThrownBy(() -> dispatcher.dispatch(invalid))
                    .isInstanceOf(InvalidIntegrationEventException.class);
        }
        verifyNoInteractions(eventHandler);
    }

    private IntegrationEventEnvelope paymentEnvelope(
            String aggregateType,
            String aggregateId,
            Instant occurredAt,
            JsonNode payload
    ) {
        return new IntegrationEventEnvelope(
                "ievt_payment",
                PaymentSucceededEventV1.EVENT_TYPE,
                aggregateType,
                aggregateId,
                occurredAt,
                payload
        );
    }

    private IntegrationEventEnvelope refundEnvelope(
            String aggregateType,
            String aggregateId,
            Instant occurredAt,
            JsonNode payload
    ) {
        return new IntegrationEventEnvelope(
                "ievt_refund",
                RefundSucceededEventV1.EVENT_TYPE,
                aggregateType,
                aggregateId,
                occurredAt,
                payload
        );
    }

    private JsonNode paymentPayload(
            String paymentPublicId,
            long amountMinor,
            String currency,
            Instant occurredAt
    ) {
        return objectMapper.valueToTree(new PaymentSucceededEventV1(
                15L,
                paymentPublicId,
                amountMinor,
                currency,
                occurredAt
        ));
    }

    private JsonNode refundPayload(
            String refundPublicId,
            long amountMinor,
            String currency,
            Instant occurredAt
    ) {
        return objectMapper.valueToTree(new RefundSucceededEventV1(
                15L,
                refundPublicId,
                "pi_for_" + refundPublicId,
                amountMinor,
                currency,
                occurredAt
        ));
    }

    private static LedgerPostingResult created(String publicId) {
        return new LedgerPostingResult(LedgerPostingOutcome.CREATED, publicId);
    }
}
