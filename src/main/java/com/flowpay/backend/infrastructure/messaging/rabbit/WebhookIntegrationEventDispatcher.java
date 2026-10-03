package com.flowpay.backend.infrastructure.messaging.rabbit;

import com.flowpay.backend.common.money.Money;
import com.flowpay.backend.infrastructure.messaging.outbox.IntegrationEventEnvelope;
import com.flowpay.backend.payment.application.event.*;
import com.flowpay.backend.refund.application.event.*;
import com.flowpay.backend.webhook.application.MaterializeWebhookEventCommand;
import com.flowpay.backend.webhook.application.WebhookEventMaterializationService;
import com.flowpay.backend.webhook.application.WebhookMaterializationResult;
import com.flowpay.backend.webhook.domain.WebhookEventType;
import com.flowpay.backend.webhook.domain.WebhookResourceType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;

@Component
@RequiredArgsConstructor
public class WebhookIntegrationEventDispatcher {
    private final ObjectMapper mapper;
    private final WebhookEventMaterializationService materializer;

    public WebhookMaterializationResult dispatch(IntegrationEventEnvelope envelope) {
        return materializer.materialize(command(envelope));
    }

    // Explicit transport mapping validates the stable typed contracts and domain value factories.
    // It deliberately does not ask Payment/Refund for their current state.
    MaterializeWebhookEventCommand command(IntegrationEventEnvelope envelope) {
        try {
            return switch (envelope.eventType()) {
                case PaymentProcessingEventV1.EVENT_TYPE -> {
                    var source = read(envelope, PaymentProcessingEventV1.class);
                    yield command(envelope, WebhookEventType.PAYMENT_PROCESSING, source.merchantInternalId(),
                            source.paymentPublicId(), null, source.amountMinor(), source.currency(),
                            null, null, source.occurredAt());
                }
                case PaymentSucceededEventV1.EVENT_TYPE -> {
                    var source = read(envelope, PaymentSucceededEventV1.class);
                    yield command(envelope, WebhookEventType.PAYMENT_SUCCEEDED, source.merchantInternalId(),
                            source.paymentPublicId(), null, source.amountMinor(), source.currency(),
                            null, null, source.occurredAt());
                }
                case PaymentFailedEventV1.EVENT_TYPE -> {
                    var source = read(envelope, PaymentFailedEventV1.class);
                    yield command(envelope, WebhookEventType.PAYMENT_FAILED, source.merchantInternalId(),
                            source.paymentPublicId(), null, source.amountMinor(), source.currency(),
                            source.failureCode(), source.failureMessage(), source.occurredAt());
                }
                case RefundProcessingEventV1.EVENT_TYPE -> {
                    var source = read(envelope, RefundProcessingEventV1.class);
                    yield command(envelope, WebhookEventType.REFUND_PROCESSING, source.merchantInternalId(),
                            source.refundPublicId(), source.paymentPublicId(), source.amountMinor(), source.currency(),
                            null, null, source.occurredAt());
                }
                case RefundSucceededEventV1.EVENT_TYPE -> {
                    var source = read(envelope, RefundSucceededEventV1.class);
                    yield command(envelope, WebhookEventType.REFUND_SUCCEEDED, source.merchantInternalId(),
                            source.refundPublicId(), source.paymentPublicId(), source.amountMinor(), source.currency(),
                            null, null, source.occurredAt());
                }
                case RefundFailedEventV1.EVENT_TYPE -> {
                    var source = read(envelope, RefundFailedEventV1.class);
                    yield command(envelope, WebhookEventType.REFUND_FAILED, source.merchantInternalId(),
                            source.refundPublicId(), source.paymentPublicId(), source.amountMinor(), source.currency(),
                            source.failureCode(), source.failureMessage(), source.occurredAt());
                }
                default -> throw new InvalidIntegrationEventException("Unsupported Webhook source event type");
            };
        } catch (JacksonException | IllegalArgumentException | NullPointerException exception) {
            // Do not attach deserialization causes which can contain the source body.
            throw new InvalidIntegrationEventException("Invalid Webhook source event facts");
        }
    }

    private <T> T read(IntegrationEventEnvelope envelope, Class<T> contract) {
        var payload = envelope.payload();
        if (!payload.path("merchantInternalId").isIntegralNumber()
                || !payload.path("merchantInternalId").canConvertToLong()
                || !payload.path("amountMinor").isIntegralNumber()
                || !payload.path("amountMinor").canConvertToLong()) {
            throw new InvalidIntegrationEventException("Webhook source money and ownership must be integers");
        }
        for (String field : java.util.List.of("paymentPublicId", "currency", "occurredAt")) {
            requireString(payload, field);
        }
        if (envelope.aggregateType().equals("REFUND")) {
            requireString(payload, "refundPublicId");
        }
        if (envelope.eventType().equals(PaymentFailedEventV1.EVENT_TYPE)
                || envelope.eventType().equals(RefundFailedEventV1.EVENT_TYPE)) {
            requireString(payload, "failureCode");
            requireString(payload, "failureMessage");
        }
        return mapper.treeToValue(payload, contract);
    }

    private static void requireString(tools.jackson.databind.JsonNode payload, String field) {
        if (!payload.path(field).isString()) {
            throw new InvalidIntegrationEventException("Webhook source text facts must be strings");
        }
    }

    private static MaterializeWebhookEventCommand command(IntegrationEventEnvelope envelope, WebhookEventType type,
            long merchant, String resourceId, String paymentId, long amount, String currency,
            String failureCode, String failureMessage, Instant occurredAt) {
        if (!WebhookResourceType.forEventType(type).name().equals(envelope.aggregateType())
                || !resourceId.equals(envelope.aggregateId()) || !occurredAt.equals(envelope.occurredAt())) {
            throw new InvalidIntegrationEventException("Webhook source envelope and payload must agree");
        }
        return new MaterializeWebhookEventCommand(envelope.eventId(), merchant, type, resourceId,
                paymentId, Money.of(amount, currency), failureCode, failureMessage, occurredAt);
    }
}
