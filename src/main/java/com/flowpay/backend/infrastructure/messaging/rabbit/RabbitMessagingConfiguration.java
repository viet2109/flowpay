package com.flowpay.backend.infrastructure.messaging.rabbit;

import com.flowpay.backend.infrastructure.messaging.FlowPayMessagingProperties;
import com.flowpay.backend.payment.application.event.PaymentSucceededEventV1;
import com.flowpay.backend.refund.application.event.RefundSucceededEventV1;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(FlowPayMessagingProperties.class)
public class RabbitMessagingConfiguration {

    @Bean
    Declarables flowPayEventTopology(FlowPayMessagingProperties properties) {
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

        return new Declarables(
                eventExchange,
                ledgerQueue,
                paymentBinding,
                refundBinding,
                deadLetterExchange,
                ledgerDeadLetterQueue,
                deadLetterBinding
        );
    }
}
