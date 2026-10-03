package com.flowpay.backend.infrastructure.messaging.rabbit;

import com.flowpay.backend.infrastructure.messaging.FlowPayMessagingProperties;
import com.flowpay.backend.infrastructure.messaging.WebhookMessagingProperties;
import com.flowpay.backend.payment.application.event.PaymentSucceededEventV1;
import com.flowpay.backend.payment.application.event.PaymentProcessingEventV1;
import com.flowpay.backend.payment.application.event.PaymentFailedEventV1;
import com.flowpay.backend.refund.application.event.RefundSucceededEventV1;
import com.flowpay.backend.refund.application.event.RefundProcessingEventV1;
import com.flowpay.backend.refund.application.event.RefundFailedEventV1;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.boot.amqp.autoconfigure.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties({FlowPayMessagingProperties.class, WebhookMessagingProperties.class})
public class RabbitMessagingConfiguration {

    static final String LEDGER_LISTENER_CONTAINER_FACTORY =
            "ledgerRabbitListenerContainerFactory";
    static final String WEBHOOK_LISTENER_CONTAINER_FACTORY = "webhookRabbitListenerContainerFactory";

    @Bean
    Declarables flowPayEventTopology(FlowPayMessagingProperties properties, WebhookMessagingProperties webhook) {
        FlowPayMessagingProperties.Topology names = properties.topology();
        TopicExchange eventExchange = ExchangeBuilder
                .topicExchange(names.exchange())
                .durable(true)
                .build();
        DirectExchange deadLetterExchange = ExchangeBuilder
                .directExchange(names.deadLetterExchange())
                .durable(true)
                .build();
        Queue ledgerQueue = QueueBuilder
                .durable(names.ledgerQueue())
                .deadLetterExchange(names.deadLetterExchange())
                .deadLetterRoutingKey(names.deadLetterRoutingKey())
                .build();
        Queue ledgerDeadLetterQueue = QueueBuilder
                .durable(names.ledgerDeadLetterQueue())
                .build();
        Binding paymentBinding = BindingBuilder.bind(ledgerQueue)
                .to(eventExchange)
                .with(PaymentSucceededEventV1.EVENT_TYPE);
        Binding refundBinding = BindingBuilder.bind(ledgerQueue)
                .to(eventExchange)
                .with(RefundSucceededEventV1.EVENT_TYPE);
        Binding deadLetterBinding = BindingBuilder.bind(ledgerDeadLetterQueue)
                .to(deadLetterExchange)
                .with(names.deadLetterRoutingKey());

        var webhookNames = webhook.topology();
        if (new HashSet<>(List.of(names.ledgerQueue(), names.ledgerDeadLetterQueue(),
                webhookNames.webhookQueue(), webhookNames.webhookDeadLetterQueue())).size() != 4
                || names.deadLetterRoutingKey().equals(webhookNames.webhookDeadLetterRoutingKey())) {
            throw new IllegalArgumentException("Ledger and Webhook must have independent queues and DLQ routing");
        }
        Queue webhookQueue = QueueBuilder.durable(webhookNames.webhookQueue())
                .deadLetterExchange(names.deadLetterExchange())
                .deadLetterRoutingKey(webhookNames.webhookDeadLetterRoutingKey()).build();
        Queue webhookDlq = QueueBuilder.durable(webhookNames.webhookDeadLetterQueue()).build();
        List<Declarable> declarations = new ArrayList<>(List.of(
                eventExchange,
                ledgerQueue,
                paymentBinding,
                refundBinding,
                deadLetterExchange,
                ledgerDeadLetterQueue,
                deadLetterBinding, webhookQueue, webhookDlq,
                BindingBuilder.bind(webhookDlq).to(deadLetterExchange).with(webhookNames.webhookDeadLetterRoutingKey())
        ));
        for (String routingKey : List.of(PaymentProcessingEventV1.EVENT_TYPE, PaymentSucceededEventV1.EVENT_TYPE,
                PaymentFailedEventV1.EVENT_TYPE, RefundProcessingEventV1.EVENT_TYPE,
                RefundSucceededEventV1.EVENT_TYPE, RefundFailedEventV1.EVENT_TYPE)) {
            declarations.add(BindingBuilder.bind(webhookQueue).to(eventExchange).with(routingKey));
        }
        return new Declarables(declarations);
    }

    @Bean(name = LEDGER_LISTENER_CONTAINER_FACTORY)
    SimpleRabbitListenerContainerFactory ledgerRabbitListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory,
            FlowPayMessagingProperties properties
    ) {
        return listenerFactory(configurer, connectionFactory, properties.ledgerConsumer().retry());
    }

    @Bean(name = WEBHOOK_LISTENER_CONTAINER_FACTORY)
    SimpleRabbitListenerContainerFactory webhookRabbitListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer, ConnectionFactory connectionFactory,
            WebhookMessagingProperties properties) {
        return listenerFactory(configurer, connectionFactory, properties.webhookConsumer().retry());
    }

    private static SimpleRabbitListenerContainerFactory listenerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer, ConnectionFactory connectionFactory,
            FlowPayMessagingProperties.Retry retry) {
        SimpleRabbitListenerContainerFactory factory =
                new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setDefaultRequeueRejected(false);
        factory.setAdviceChain(RetryInterceptorBuilder.stateless()
                .maxRetries(retry.maxAttempts() - 1)
                .backOffOptions(
                        retry.initialInterval().toMillis(),
                        retry.multiplier(),
                        retry.maxInterval().toMillis()
                )
                .recoverer(new RejectAndDontRequeueRecoverer())
                .build());
        return factory;
    }
}
