package com.flowpay.backend.infrastructure.messaging.rabbit;

import com.flowpay.backend.infrastructure.messaging.FlowPayMessagingProperties;
import com.flowpay.backend.infrastructure.messaging.WebhookMessagingProperties;
import com.flowpay.backend.infrastructure.messaging.IntegrationEventTransportPublisher;
import com.flowpay.backend.infrastructure.messaging.outbox.IntegrationEventEnvelope;
import com.flowpay.backend.testing.PostgresIntegrationTest;
import com.flowpay.backend.webhook.application.*;
import com.flowpay.backend.webhook.domain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.Clock;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WebhookIntegrationEventConsumerIntegrationTest extends PostgresIntegrationTest {
    @Container private static final RabbitMQContainer RABBITMQ = new RabbitMQContainer("rabbitmq:4.3-management-alpine");
    private static final Instant OCCURRED = Instant.parse("2026-10-03T01:02:03.123456Z");
    @Autowired private IntegrationEventTransportPublisher publisher;
    @Autowired private WebhookEndpointRepository endpoints;
    @Autowired private WebhookEventRepository events;
    @Autowired private FlowPayMessagingProperties messaging;
    @Autowired private WebhookMessagingProperties webhook;
    @Autowired private RabbitAdmin admin;
    @Autowired private RabbitTemplate rabbit;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper mapper;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private WebhookSecretCipher cipher;
    @Autowired private WebhookDeliveryRepository deliveries;
    @Autowired private WebhookDeliveryAttemptRepository attempts;
    @Autowired private WebhookDeliveryExecutionService execution;
    @MockitoBean private WebhookHttpClientPort http;
    @MockitoBean private Clock clock;
    private final AtomicReference<Instant> now = new AtomicReference<>(OCCURRED);
    private long merchant;

    @DynamicPropertySource
    static void rabbitProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.host", RABBITMQ::getHost);
        registry.add("spring.rabbitmq.port", RABBITMQ::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBITMQ::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBITMQ::getAdminPassword);
        registry.add("flowpay.messaging.webhook-consumer.enabled", () -> "true");
        registry.add("flowpay.messaging.ledger-consumer.enabled", () -> "true");
        registry.add("flowpay.messaging.webhook-consumer.retry.initial-interval", () -> "10ms");
        registry.add("flowpay.messaging.webhook-consumer.retry.max-interval", () -> "50ms");
    }

    @BeforeEach
    void cleanState() {
        now.set(OCCURRED);
        when(clock.instant()).thenAnswer(call -> now.get());
        for (String queue : new String[]{webhook.topology().webhookQueue(), webhook.topology().webhookDeadLetterQueue(),
                messaging.topology().ledgerQueue(), messaging.topology().ledgerDeadLetterQueue()}) {
            admin.purgeQueue(queue, true);
        }
        jdbc.execute("""
                TRUNCATE TABLE merchants, users, ledger_entries, ledger_transactions, ledger_accounts
                RESTART IDENTITY CASCADE
                """);
        merchant = jdbc.queryForObject("""
                INSERT INTO merchants (public_id, name, status, created_at, updated_at)
                VALUES ('mrc_webhook_consumer', 'Webhook Consumer', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                RETURNING id
                """, Long.class);
    }

    @Test
    void allSixTypesMaterializeIndependentSnapshotsWhileLedgerStillPostsOnlySuccesses() {
        endpoint("one");
        endpoint("two");
        for (var type : WebhookEventType.values()) {
            var event = envelope(type, 10000);
            assertThat(publisher.publish(event).confirmed()).isTrue();
            assertThat(publisher.publish(event).confirmed()).isTrue();
        }
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(count("webhook_events")).isEqualTo(6);
            assertThat(count("webhook_deliveries")).isEqualTo(12);
            assertThat(count("ledger_transactions")).isEqualTo(2);
            assertThat(count("ledger_entries")).isEqualTo(4);
        });
        assertThat(jdbc.queryForList("SELECT event_type FROM webhook_events", String.class))
                .containsExactlyInAnyOrder(Arrays.stream(WebhookEventType.values()).map(WebhookEventType::value).toArray(String[]::new));
        for (var type : WebhookEventType.values()) {
            var saved = events.findBySourceEventId("ievt_" + type.name()).orElseThrow();
            assertThat(saved.occurredAt()).isEqualTo(OCCURRED);
            assertThat(saved.payload()).doesNotContain("merchantInternalId", "orderId", "ievt_");
        }
        assertThat(queueSize(webhook.topology().webhookDeadLetterQueue())).isZero();
        assertThat(queueSize(messaging.topology().ledgerDeadLetterQueue())).isZero();
    }

    @Test
    void equivalentRedeliveryIsAckedWithoutIncludingAnEndpointConfiguredLater() {
        var source = envelope(WebhookEventType.PAYMENT_PROCESSING, 10000);
        assertThat(publisher.publish(source).confirmed()).isTrue();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(count("webhook_events")).isOne());
        var original = events.findBySourceEventId(source.eventId()).orElseThrow();
        endpoint("late");
        // Different JSON order/spacing and normalized currency are still equivalent facts.
        var payload = mapper.readTree("""
                {"currency":" vnd ","amountMinor":10000,"paymentPublicId":"pi_source",
                 "occurredAt":"%s","merchantInternalId":%d}
                """.formatted(OCCURRED, merchant));
        publisher.publish(new IntegrationEventEnvelope(source.eventId(), source.eventType(), source.aggregateType(),
                source.aggregateId(), source.occurredAt(), payload));
        // FIFO marker proves the preceding redelivery finished, not merely that DB counts stayed unchanged.
        publisher.publish(envelope(WebhookEventType.REFUND_PROCESSING, 10000));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(count("webhook_events")).isEqualTo(2);
            assertThat(count("webhook_deliveries")).isOne();
        });
        assertThat(events.findBySourceEventId(source.eventId()).orElseThrow().publicId()).isEqualTo(original.publicId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM webhook_deliveries WHERE webhook_event_id = ?",
                Long.class, original.internalId())).isZero();
        assertThat(queueSize(webhook.topology().webhookDeadLetterQueue())).isZero();
    }

    @Test
    void malformedAndContradictoryMessagesExhaustBoundedRetryAndGoToWebhookDlqOnly() {
        endpoint("one");
        publisher.publish(envelope(WebhookEventType.PAYMENT_PROCESSING, 10000));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(count("webhook_deliveries")).isOne());
        var original = events.findBySourceEventId("ievt_PAYMENT_PROCESSING").orElseThrow();
        publisher.publish(envelope(WebhookEventType.PAYMENT_PROCESSING, 9000));
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setMessageId("ievt_malformed");
        rabbit.send(messaging.topology().exchange(), "refund.processing.v1",
                new Message("{malformed}".getBytes(StandardCharsets.UTF_8), properties));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(queueSize(webhook.topology().webhookDeadLetterQueue())).isEqualTo(2));
        assertThat(count("webhook_events")).isOne();
        assertThat(count("webhook_deliveries")).isOne();
        assertThat(events.findBySourceEventId(original.sourceEventId()).orElseThrow().payload()).isEqualTo(original.payload());
        assertThat(count("ledger_transactions")).isZero();
        assertThat(queueSize(messaging.topology().ledgerDeadLetterQueue())).isZero();
        for (int i = 0; i < 2; i++) {
            Message dead = rabbit.receive(webhook.topology().webhookDeadLetterQueue(), 5000);
            assertThat(dead).isNotNull();
            assertThat(dead.getMessageProperties().getHeaders()).containsKey("x-death");
        }
    }

    @Test
    void merchantDeliveryDeadAndMaterializerDlqAreIndependentFailureDomains() {
        endpoint("one");
        var source = envelope(WebhookEventType.PAYMENT_PROCESSING, 10000);
        assertThat(publisher.publish(source).confirmed()).isTrue();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(count("webhook_deliveries")).isOne());
        var event = events.findBySourceEventId(source.eventId()).orElseThrow();
        long deliveryId = jdbc.queryForObject("SELECT id FROM webhook_deliveries", Long.class);
        when(http.send(any())).thenReturn(WebhookHttpDeliveryResult.http(500, 1));
        var worker = new WebhookDeliveryWorker(execution, http, cipher,
                new WebhookDeliveryWorkerProperties(true, Duration.ofSeconds(1), 100, Duration.ofSeconds(30)), clock);
        for (int no = 1; no <= 6; no++) {
            worker.deliverBatch();
            var delivery = deliveries.findByInternalId(deliveryId).orElseThrow();
            assertThat(delivery.attemptCount()).isEqualTo(no);
            if (no < 6) now.set(delivery.nextAttemptAt());
        }
        assertThat(deliveries.findByInternalId(deliveryId).orElseThrow().status()).isEqualTo(WebhookDeliveryStatus.DEAD);
        assertThat(attempts.findAllByDeliveryId(deliveryId)).hasSize(6);
        assertThat(queueSize(webhook.topology().webhookDeadLetterQueue())).isZero();

        // Equivalent broker redelivery does not revive DEAD or create another delivery.
        assertThat(publisher.publish(source).confirmed()).isTrue();
        // Contradictory source facts fail materialization and are dead-lettered independently of HTTP history.
        assertThat(publisher.publish(envelope(WebhookEventType.PAYMENT_PROCESSING, 9000)).confirmed()).isTrue();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(queueSize(webhook.topology().webhookDeadLetterQueue())).isOne());
        worker.deliverBatch();
        verify(http, times(6)).send(any());
        assertThat(count("webhook_events")).isOne();
        assertThat(count("webhook_deliveries")).isOne();
        assertThat(events.findBySourceEventId(source.eventId()).orElseThrow().payload()).isEqualTo(event.payload());
        assertThat(deliveries.findByInternalId(deliveryId).orElseThrow().status()).isEqualTo(WebhookDeliveryStatus.DEAD);
        assertThat(attempts.findAllByDeliveryId(deliveryId)).hasSize(6);
        assertThat(count("ledger_transactions")).isZero();
        assertThat(queueSize(messaging.topology().ledgerDeadLetterQueue())).isZero();
    }

    private void endpoint(String suffix) {
        new TransactionTemplate(transactions).execute(status -> endpoints.save(WebhookEndpoint.create(
                "wep_" + suffix, merchant, "https://example.com/webhooks", cipher.encrypt("whsec_consumer_fixture"),
                Arrays.asList(WebhookEventType.values()), OCCURRED)));
    }

    private IntegrationEventEnvelope envelope(WebhookEventType type, long amount) {
        boolean refund = WebhookResourceType.forEventType(type) == WebhookResourceType.REFUND;
        var payload = mapper.createObjectNode();
        payload.put("merchantInternalId", merchant);
        payload.put("paymentPublicId", "pi_source");
        if (refund) payload.put("refundPublicId", "re_source");
        payload.put("amountMinor", amount);
        payload.put("currency", "VND");
        payload.put("occurredAt", OCCURRED.toString());
        if (type == WebhookEventType.PAYMENT_FAILED || type == WebhookEventType.REFUND_FAILED) {
            payload.put("failureCode", "DECLINED");
            payload.put("failureMessage", "Payment declined");
        }
        return new IntegrationEventEnvelope("ievt_" + type.name(), type.value() + ".v1",
                refund ? "REFUND" : "PAYMENT_INTENT", refund ? "re_source" : "pi_source", OCCURRED, payload);
    }

    private long count(String table) { return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class); }
    private int queueSize(String queue) { return ((Number) admin.getQueueProperties(queue).get(RabbitAdmin.QUEUE_MESSAGE_COUNT)).intValue(); }
}
