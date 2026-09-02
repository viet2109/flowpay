package com.flowpay.backend.infrastructure.messaging.rabbit;

import com.flowpay.backend.common.money.Money;
import com.flowpay.backend.infrastructure.messaging.outbox.IntegrationEventEnvelope;
import com.flowpay.backend.ledger.application.LedgerPostingResult;
import com.flowpay.backend.ledger.application.PostPaymentSucceededCommand;
import com.flowpay.backend.ledger.application.PostRefundSucceededCommand;
import com.flowpay.backend.payment.application.event.PaymentSucceededEventV1;
import com.flowpay.backend.refund.application.event.RefundSucceededEventV1;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Objects;

@Component
@RequiredArgsConstructor
public class LedgerIntegrationEventDispatcher {

    private final ObjectMapper objectMapper;
    private final LedgerIntegrationEventHandler eventHandler;

    public LedgerPostingResult dispatch(IntegrationEventEnvelope envelope) {
        IntegrationEventEnvelope event = Objects.requireNonNull(
                envelope,
                "envelope must not be null"
        );
        return switch (event.eventType()) {
            case PaymentSucceededEventV1.EVENT_TYPE -> dispatchPayment(event);
            case RefundSucceededEventV1.EVENT_TYPE -> dispatchRefund(event);
            default -> throw new InvalidIntegrationEventException(
                    "Integration event type is not supported"
            );
        };
    }

    private LedgerPostingResult dispatchPayment(IntegrationEventEnvelope envelope) {
        requireAggregateType(envelope, PaymentSucceededEventV1.AGGREGATE_TYPE);
        PaymentSucceededEventV1 event = readPayload(
                envelope.payload(),
                PaymentSucceededEventV1.class
        );
        requireEnvelopeConsistency(
                envelope,
                event.paymentPublicId(),
                event.occurredAt()
        );
        return eventHandler.handlePayment(new PostPaymentSucceededCommand(
                event.merchantInternalId(),
                event.paymentPublicId(),
                Money.of(event.amountMinor(), event.currency()),
                event.occurredAt()
        ));
    }

    private LedgerPostingResult dispatchRefund(IntegrationEventEnvelope envelope) {
        requireAggregateType(envelope, RefundSucceededEventV1.AGGREGATE_TYPE);
        RefundSucceededEventV1 event = readPayload(
                envelope.payload(),
                RefundSucceededEventV1.class
        );
        requireEnvelopeConsistency(
                envelope,
                event.refundPublicId(),
                event.occurredAt()
        );
        return eventHandler.handleRefund(new PostRefundSucceededCommand(
                event.merchantInternalId(),
                event.refundPublicId(),
                Money.of(event.amountMinor(), event.currency()),
                event.occurredAt()
        ));
    }

    private <T> T readPayload(JsonNode payload, Class<T> contractType) {
        try {
            return objectMapper.treeToValue(payload, contractType);
        } catch (JacksonException | IllegalArgumentException exception) {
            throw new InvalidIntegrationEventException(
                    "Integration event payload is invalid",
                    exception
            );
        }
    }

    private static void requireAggregateType(
            IntegrationEventEnvelope envelope,
            String expectedAggregateType
    ) {
        if (!expectedAggregateType.equals(envelope.aggregateType())) {
            throw new InvalidIntegrationEventException(
                    "Integration event aggregateType is invalid"
            );
        }
    }

    private static void requireEnvelopeConsistency(
            IntegrationEventEnvelope envelope,
            String payloadAggregateId,
            Instant payloadOccurredAt
    ) {
        if (!envelope.aggregateId().equals(payloadAggregateId)) {
            throw new InvalidIntegrationEventException(
                    "Envelope aggregateId must match payload aggregate ID"
            );
        }
        if (!envelope.occurredAt().equals(payloadOccurredAt)) {
            throw new InvalidIntegrationEventException(
                    "Envelope occurredAt must match payload occurredAt"
            );
        }
    }
}
