package com.flowpay.backend.infrastructure.messaging.rabbit;

import com.flowpay.backend.infrastructure.messaging.FlowPayMessagingProperties;
import com.flowpay.backend.infrastructure.messaging.IntegrationEventPublicationStatus;
import com.flowpay.backend.infrastructure.messaging.IntegrationEventTransportPublisher;
import com.flowpay.backend.infrastructure.messaging.outbox.IntegrationEventEnvelope;
import com.flowpay.backend.payment.application.event.PaymentSucceededEventV1;
import com.flowpay.backend.refund.application.event.RefundSucceededEventV1;
import com.flowpay.backend.testing.PostgresIntegrationTest;
import com.rabbitmq.client.GetResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class RabbitMessagingIntegrationTest extends PostgresIntegrationTest {

    @Container
    private static final RabbitMQContainer RABBITMQ =
            new RabbitMQContainer("rabbitmq:4.3-management-alpine");

    @Autowired
    private IntegrationEventTransportPublisher publisher;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    @Autowired
    private Declarables flowPayEventTopology;

    @Autowired
    private FlowPayMessagingProperties properties;

    @Autowired
    private ObjectMapper objectMapper;

    @DynamicPropertySource
    static void rabbitProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.host", RABBITMQ::getHost);
        registry.add("spring.rabbitmq.port", RABBITMQ::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBITMQ::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBITMQ::getAdminPassword);
    }

    @BeforeEach
    void initializeAndCleanTopology() {
        rabbitAdmin.initialize();
        purgeQueues();
    }

    @AfterEach
    void cleanQueues() {
        purgeQueues();
    }

    @Test
    void shouldDeclareExactDurableTopologyWithDeadLettering() {
        FlowPayMessagingProperties.Topology names = properties.topology();
        TopicExchange eventExchange = flowPayEventTopology
                .getDeclarablesByType(TopicExchange.class)
                .getFirst();
        DirectExchange deadLetterExchange = flowPayEventTopology
                .getDeclarablesByType(DirectExchange.class)
                .getFirst();
        List<Queue> queues = flowPayEventTopology.getDeclarablesByType(Queue.class);
        Queue ledgerQueue = queueNamed(queues, names.ledgerQueue());
        Queue deadLetterQueue = queueNamed(queues, names.ledgerDeadLetterQueue());

        assertThat(eventExchange.getName()).isEqualTo(names.exchange());
        assertThat(eventExchange.isDurable()).isTrue();
        assertThat(eventExchange.isAutoDelete()).isFalse();
        assertThat(deadLetterExchange.getName()).isEqualTo(names.deadLetterExchange());
        assertThat(deadLetterExchange.isDurable()).isTrue();
        assertThat(deadLetterExchange.isAutoDelete()).isFalse();
        assertDurableQueue(ledgerQueue);
        assertDurableQueue(deadLetterQueue);
        assertThat(ledgerQueue.getArguments())
                .containsEntry("x-dead-letter-exchange", names.deadLetterExchange())
                .containsEntry("x-dead-letter-routing-key", names.deadLetterRoutingKey());

        List<Binding> bindings = flowPayEventTopology.getDeclarablesByType(Binding.class);
        assertThat(bindings)
                .extracting(Binding::getExchange, Binding::getDestination, Binding::getRoutingKey)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(
                                names.exchange(),
                                names.ledgerQueue(),
                                PaymentSucceededEventV1.EVENT_TYPE
                        ),
                        org.assertj.core.groups.Tuple.tuple(
                                names.exchange(),
                                names.ledgerQueue(),
                                RefundSucceededEventV1.EVENT_TYPE
                        ),
                        org.assertj.core.groups.Tuple.tuple(
                                names.deadLetterExchange(),
                                names.ledgerDeadLetterQueue(),
                                names.deadLetterRoutingKey()
                        )
                );
        assertThat(bindings).extracting(Binding::getRoutingKey)
                .doesNotContain("#", "*");

        assertThat(rabbitAdmin.getQueueProperties(names.ledgerQueue())).isNotNull();
        assertThat(rabbitAdmin.getQueueProperties(names.ledgerDeadLetterQueue())).isNotNull();
        assertThat(properties.ledgerConsumer().retry()).isEqualTo(
                new FlowPayMessagingProperties.Retry(
                        3,
                        Duration.ofMillis(500),
                        2.0,
                        Duration.ofSeconds(5)
                )
        );
    }

    @Test
    void shouldRouteBothEventsAndPreserveEnvelopeAndMessageMetadata() throws Exception {
        IntegrationEventEnvelope payment = envelope(
                "ievt_rabbit_payment",
                PaymentSucceededEventV1.EVENT_TYPE,
                "PAYMENT_INTENT",
                "pi_rabbit",
                "{\"merchantInternalId\":11,\"amountMinor\":100000,\"currency\":\"VND\"}"
        );
        IntegrationEventEnvelope refund = envelope(
                "ievt_rabbit_refund",
                RefundSucceededEventV1.EVENT_TYPE,
                "REFUND",
                "re_rabbit",
                "{\"merchantInternalId\":11,\"paymentPublicId\":\"pi_rabbit\",\"amountMinor\":25000}"
        );

        assertThat(publisher.publish(payment).status())
                .isEqualTo(IntegrationEventPublicationStatus.CONFIRMED);
        assertThat(publisher.publish(refund).status())
                .isEqualTo(IntegrationEventPublicationStatus.CONFIRMED);

        Message first = receive(properties.topology().ledgerQueue());
        Message second = receive(properties.topology().ledgerQueue());
        assertMessage(first, payment);
        assertMessage(second, refund);
        assertThat(rabbitTemplate.receive(properties.topology().ledgerQueue(), 100L)).isNull();
    }

    @Test
    void shouldReturnUnknownRouteAndDeadLetterRejectedLedgerMessage() throws Exception {
        IntegrationEventEnvelope unknown = envelope(
                "ievt_rabbit_unknown",
                "merchant.unknown.v1",
                "MERCHANT",
                "mrc_unknown",
                "{}"
        );

        assertThat(publisher.publish(unknown).status())
                .isEqualTo(IntegrationEventPublicationStatus.UNROUTABLE);
        assertThat(rabbitTemplate.receive(properties.topology().ledgerQueue(), 100L)).isNull();

        IntegrationEventEnvelope payment = envelope(
                "ievt_rabbit_dead_letter",
                PaymentSucceededEventV1.EVENT_TYPE,
                "PAYMENT_INTENT",
                "pi_dead_letter",
                "{\"amountMinor\":50000}"
        );
        assertThat(publisher.publish(payment).status())
                .isEqualTo(IntegrationEventPublicationStatus.CONFIRMED);
        rejectSingleLedgerMessage();

        Message deadLetter = receive(properties.topology().ledgerDeadLetterQueue());
        assertThat(deadLetter.getMessageProperties().getMessageId())
                .isEqualTo(payment.eventId());
        assertThat(objectMapper.readTree(deadLetter.getBody()).path("payload"))
                .isEqualTo(payment.payload());
    }

    @Test
    void shouldCorrelateConcurrentConfirmsAndReturnsToTheirOwnEventIds() throws Exception {
        int eventCount = 16;
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<CompletableFuture<Publication>> futures = IntStream.range(0, eventCount)
                    .mapToObj(index -> CompletableFuture.supplyAsync(
                            () -> publishConcurrent(index),
                            executor
                    ))
                    .toList();
            List<Publication> publications = futures.stream()
                    .map(CompletableFuture::join)
                    .toList();

            assertThat(publications).hasSize(eventCount);
            assertThat(publications.stream()
                    .filter(publication -> publication.status()
                            == IntegrationEventPublicationStatus.CONFIRMED))
                    .extracting(Publication::eventId)
                    .containsExactlyInAnyOrderElementsOf(expectedIds(eventCount, true));
            assertThat(publications.stream()
                    .filter(publication -> publication.status()
                            == IntegrationEventPublicationStatus.UNROUTABLE))
                    .extracting(Publication::eventId)
                    .containsExactlyInAnyOrderElementsOf(expectedIds(eventCount, false));
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private Publication publishConcurrent(int index) {
        boolean routable = index % 2 == 0;
        String eventId = "ievt_concurrent_" + index;
        try {
            IntegrationEventEnvelope envelope = envelope(
                    eventId,
                    routable
                            ? PaymentSucceededEventV1.EVENT_TYPE
                            : "unknown.concurrent." + index,
                    "PAYMENT_INTENT",
                    "pi_concurrent_" + index,
                    "{\"sequence\":" + index + "}"
            );
            return new Publication(eventId, publisher.publish(envelope).status());
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private void assertMessage(Message message, IntegrationEventEnvelope expected)
            throws Exception {
        assertThat(message.getMessageProperties().getReceivedDeliveryMode())
                .isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(message.getMessageProperties().getContentType())
                .isEqualTo("application/json");
        assertThat(message.getMessageProperties().getMessageId())
                .isEqualTo(expected.eventId());
        assertThat((String) message.getMessageProperties().getHeader(
                ConfirmedRabbitIntegrationEventPublisher.EVENT_TYPE_HEADER
        )).isEqualTo(expected.eventType());
        assertThat(message.getMessageProperties().getHeaders().keySet())
                .doesNotContain("__TypeId__", "password", "username", "authorization");

        JsonNode envelope = objectMapper.readTree(message.getBody());
        assertThat(envelope.path("eventId").asText()).isEqualTo(expected.eventId());
        assertThat(envelope.path("eventType").asText()).isEqualTo(expected.eventType());
        assertThat(envelope.path("aggregateType").asText())
                .isEqualTo(expected.aggregateType());
        assertThat(envelope.path("aggregateId").asText()).isEqualTo(expected.aggregateId());
        assertThat(envelope.path("occurredAt").asText())
                .isEqualTo(expected.occurredAt().toString());
        assertThat(envelope.path("payload")).isEqualTo(expected.payload());
        assertThat(new String(message.getBody(), StandardCharsets.UTF_8))
                .doesNotContain("password", "authorization", "guest", "RabbitMQ");
    }

    private void rejectSingleLedgerMessage() {
        rabbitTemplate.execute(channel -> {
            GetResponse response = channel.basicGet(
                    properties.topology().ledgerQueue(),
                    false
            );
            assertThat(response).isNotNull();
            channel.basicReject(response.getEnvelope().getDeliveryTag(), false);
            return null;
        });
    }

    private Message receive(String queueName) {
        Message message = rabbitTemplate.receive(queueName, 5_000L);
        assertThat(message).isNotNull();
        return message;
    }

    private void purgeQueues() {
        rabbitAdmin.purgeQueue(properties.topology().ledgerQueue(), true);
        rabbitAdmin.purgeQueue(properties.topology().ledgerDeadLetterQueue(), true);
    }

    private static Queue queueNamed(List<Queue> queues, String name) {
        return queues.stream()
                .filter(queue -> queue.getName().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static void assertDurableQueue(Queue queue) {
        assertThat(queue.isDurable()).isTrue();
        assertThat(queue.isExclusive()).isFalse();
        assertThat(queue.isAutoDelete()).isFalse();
    }

    private static Set<String> expectedIds(int eventCount, boolean routable) {
        return IntStream.range(0, eventCount)
                .filter(index -> (index % 2 == 0) == routable)
                .mapToObj(index -> "ievt_concurrent_" + index)
                .collect(java.util.stream.Collectors.toSet());
    }

    private IntegrationEventEnvelope envelope(
            String eventId,
            String eventType,
            String aggregateType,
            String aggregateId,
            String payload
    ) throws Exception {
        return new IntegrationEventEnvelope(
                eventId,
                eventType,
                aggregateType,
                aggregateId,
                Instant.parse("2026-09-02T05:00:00Z"),
                objectMapper.readTree(payload)
        );
    }

    private record Publication(
            String eventId,
            IntegrationEventPublicationStatus status
    ) {
    }
}
