package com.flowpay.backend.infrastructure.messaging.rabbit;

import com.flowpay.backend.common.observability.AsyncWorkTracing;
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
    private final AsyncWorkTracing tracing;

    @RabbitListener(
            id = "flowpayLedgerIntegrationEventListener",
            queues = "${flowpay.messaging.topology.ledger-queue}",
            containerFactory = RabbitMessagingConfiguration.LEDGER_LISTENER_CONTAINER_FACTORY
    )
    public void consume(Message message) {
        tracing.call(AsyncWorkTracing.Operation.LEDGER_CONSUME,
                message.getMessageProperties().getHeaders().get("traceparent"), () -> {
                    eventDispatcher.dispatch(envelopeParser.parse(message));
                    return true;
                }, ignored -> AsyncWorkTracing.Outcome.COMPLETED);
    }
}
