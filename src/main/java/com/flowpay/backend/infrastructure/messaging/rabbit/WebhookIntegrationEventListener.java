package com.flowpay.backend.infrastructure.messaging.rabbit;

import com.flowpay.backend.common.observability.AsyncWorkTracing;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "flowpay.messaging.webhook-consumer", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class WebhookIntegrationEventListener {
    private final IntegrationEventEnvelopeParser parser;
    private final WebhookIntegrationEventDispatcher dispatcher;
    private final AsyncWorkTracing tracing;

    @RabbitListener(id = "flowpayWebhookIntegrationEventListener",
            queues = "${flowpay.messaging.topology.webhook-queue}",
            containerFactory = RabbitMessagingConfiguration.WEBHOOK_LISTENER_CONTAINER_FACTORY)
    public void consume(Message message) {
        // AUTO ACK follows the application transaction's successful return, never an uncommitted snapshot.
        tracing.call(AsyncWorkTracing.Operation.WEBHOOK_MATERIALIZE,
                message.getMessageProperties().getHeaders().get("traceparent"), () -> {
                    dispatcher.dispatch(parser.parse(message));
                    return true;
                }, ignored -> AsyncWorkTracing.Outcome.COMPLETED);
    }
}
