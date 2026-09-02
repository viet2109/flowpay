package com.flowpay.backend.infrastructure.messaging.rabbit;

import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "flowpay.messaging.ledger-consumer",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class LedgerIntegrationEventListener {

    private final IntegrationEventEnvelopeParser envelopeParser;
    private final LedgerIntegrationEventDispatcher eventDispatcher;

    @RabbitListener(
            id = "flowpayLedgerIntegrationEventListener",
            queues = "${flowpay.messaging.topology.ledger-queue}",
            containerFactory = RabbitMessagingConfiguration.LEDGER_LISTENER_CONTAINER_FACTORY
    )
    public void consume(Message message) {
        eventDispatcher.dispatch(envelopeParser.parse(message));
    }
}
