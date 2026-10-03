package com.flowpay.backend.infrastructure.messaging.outbox;

import com.flowpay.backend.payment.application.event.PaymentIntegrationEventPublisher;
import com.flowpay.backend.payment.application.event.PaymentSucceededEventV1;
import com.flowpay.backend.payment.application.event.PaymentProcessingEventV1;
import com.flowpay.backend.payment.application.event.PaymentFailedEventV1;
import com.flowpay.backend.refund.application.event.RefundIntegrationEventPublisher;
import com.flowpay.backend.refund.application.event.RefundSucceededEventV1;
import com.flowpay.backend.refund.application.event.RefundProcessingEventV1;
import com.flowpay.backend.refund.application.event.RefundFailedEventV1;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class TransactionalOutboxEventPublisher
        implements PaymentIntegrationEventPublisher, RefundIntegrationEventPublisher {

    private final OutboxRepository outboxRepository;
    private final IntegrationEventIdGenerator eventIdGenerator;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Override
    public void publish(PaymentSucceededEventV1 event) {
        PaymentSucceededEventV1 source = Objects.requireNonNull(
                event,
                "event must not be null"
        );
        persist(
                source.aggregateType(),
                source.aggregateId(),
                source.eventType(),
                source,
                source.occurredAt()
        );
    }

    @Override
    public void publish(RefundSucceededEventV1 event) {
        RefundSucceededEventV1 source = Objects.requireNonNull(
                event,
                "event must not be null"
        );
        persist(
                source.aggregateType(),
                source.aggregateId(),
                source.eventType(),
                source,
                source.occurredAt()
        );
    }

    @Override
    public void publish(PaymentProcessingEventV1 event) {
        PaymentProcessingEventV1 source = Objects.requireNonNull(event, "event must not be null");
        persist(source.aggregateType(), source.aggregateId(), source.eventType(), source, source.occurredAt());
    }

    @Override
    public void publish(PaymentFailedEventV1 event) {
        PaymentFailedEventV1 source = Objects.requireNonNull(event, "event must not be null");
        persist(source.aggregateType(), source.aggregateId(), source.eventType(), source, source.occurredAt());
    }

    @Override
    public void publish(RefundProcessingEventV1 event) {
        RefundProcessingEventV1 source = Objects.requireNonNull(event, "event must not be null");
        persist(source.aggregateType(), source.aggregateId(), source.eventType(), source, source.occurredAt());
    }

    @Override
    public void publish(RefundFailedEventV1 event) {
        RefundFailedEventV1 source = Objects.requireNonNull(event, "event must not be null");
        persist(source.aggregateType(), source.aggregateId(), source.eventType(), source, source.occurredAt());
    }

    private void persist(
            String aggregateType,
            String aggregateId,
            String eventType,
            Object payload,
            Instant occurredAt
    ) {
        Instant createdAt = clock.instant();
        outboxRepository.save(OutboxEvent.pending(
                eventIdGenerator.nextId(),
                aggregateType,
                aggregateId,
                eventType,
                serialize(payload),
                occurredAt,
                createdAt
        ));
    }

    private String serialize(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JacksonException exception) {
            throw new IllegalStateException(
                    "Integration event payload could not be serialized",
                    exception
            );
        }
    }
}
