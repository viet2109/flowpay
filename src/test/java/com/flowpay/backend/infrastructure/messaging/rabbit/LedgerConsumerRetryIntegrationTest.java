package com.flowpay.backend.infrastructure.messaging.rabbit;

import com.flowpay.backend.common.money.Money;
import com.flowpay.backend.infrastructure.messaging.FlowPayMessagingProperties;
import com.flowpay.backend.infrastructure.messaging.IntegrationEventPublicationResult;
import com.flowpay.backend.infrastructure.messaging.IntegrationEventTransportPublisher;
import com.flowpay.backend.infrastructure.messaging.outbox.IntegrationEventEnvelope;
import com.flowpay.backend.ledger.application.LedgerPostingApi;
import com.flowpay.backend.ledger.application.LedgerPostingOutcome;
import com.flowpay.backend.ledger.application.LedgerPostingResult;
import com.flowpay.backend.ledger.application.LedgerPostingService;
import com.flowpay.backend.ledger.application.PostPaymentSucceededCommand;
import com.flowpay.backend.ledger.application.PostRefundSucceededCommand;
import com.flowpay.backend.payment.application.event.PaymentSucceededEventV1;
import com.flowpay.backend.testing.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@ActiveProfiles("test")
@Import(LedgerConsumerRetryIntegrationTest.FaultInjectionConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LedgerConsumerRetryIntegrationTest extends PostgresIntegrationTest {

    private static final Instant OCCURRED_AT =
            Instant.parse("2026-09-02T11:00:00Z");
    private static final int MAX_ATTEMPTS = 3;

    @Container
    private static final RabbitMQContainer RABBITMQ =
            new RabbitMQContainer("rabbitmq:4.3-management-alpine");

    @Autowired
    private IntegrationEventTransportPublisher transportPublisher;

    @Autowired
    private FaultInjectingLedgerPostingApi postingApi;

    @Autowired
    private FlowPayMessagingProperties properties;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @DynamicPropertySource
    static void messagingProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.host", RABBITMQ::getHost);
        registry.add("spring.rabbitmq.port", RABBITMQ::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBITMQ::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBITMQ::getAdminPassword);
        registry.add("flowpay.messaging.ledger-consumer.enabled", () -> "true");
        registry.add(
                "flowpay.messaging.ledger-consumer.retry.max-attempts",
                () -> Integer.toString(MAX_ATTEMPTS)
        );
        registry.add(
                "flowpay.messaging.ledger-consumer.retry.initial-interval",
                () -> "100ms"
        );
        registry.add(
                "flowpay.messaging.ledger-consumer.retry.multiplier",
                () -> "2.0"
        );
        registry.add(
                "flowpay.messaging.ledger-consumer.retry.max-interval",
                () -> "500ms"
        );
        registry.add(
                "flowpay.messaging.outbox.publisher-confirm-timeout",
                () -> "2s"
        );
    }

    @BeforeEach
    void cleanState() {
        dropLedgerEntryFailureTrigger();
        postingApi.reset();
        jdbcTemplate.update("""
                TRUNCATE TABLE outbox_events, ledger_entries, ledger_transactions,
                    ledger_accounts RESTART IDENTITY CASCADE
                """);
        purgeQueues();
    }

    @AfterEach
    void cleanQueues() {
        dropLedgerEntryFailureTrigger();
        purgeQueues();
    }

    @Test
    void transientFailureShouldRetryWithBoundedBackoffAndEventuallySucceed() {
        String paymentPublicId = "pi_retry_transient";
        postingApi.failNextPaymentAttempts(paymentPublicId, 2);

        publish(paymentEnvelope(
                "ievt_retry_transient",
                paymentPublicId,
                125_000L
        ));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(postingApi.paymentAttemptCount(paymentPublicId))
                    .isEqualTo(MAX_ATTEMPTS);
            assertThat(countRows("ledger_transactions")).isOne();
            assertThat(countRows("ledger_entries")).isEqualTo(2L);
        });
        assertBoundedBackoff(postingApi.paymentAttemptTimes(paymentPublicId));
        assertThat(receive(properties.topology().ledgerDeadLetterQueue(), 300L)).isNull();
        assertThat(postingApi.transactionStates()).containsOnly(true);
    }

    @Test
    void exhaustedFailureShouldDeadLetterOnceAndLeavePublishedOutboxUntouched() {
        String eventId = "ievt_retry_exhausted";
        String paymentPublicId = "pi_retry_exhausted";
        IntegrationEventEnvelope envelope = paymentEnvelope(
                eventId,
                paymentPublicId,
                225_000L
        );
        insertPublishedOutbox(envelope);
        postingApi.failNextPaymentAttempts(paymentPublicId, MAX_ATTEMPTS);

        publish(envelope);

        Message deadLetter = requireDeadLetter();
        assertThat(deadLetter.getMessageProperties().getMessageId()).isEqualTo(eventId);
        assertThat(postingApi.paymentAttemptCount(paymentPublicId))
                .isEqualTo(MAX_ATTEMPTS);
        assertBoundedBackoff(postingApi.paymentAttemptTimes(paymentPublicId));
        assertThat(outboxStatus(eventId)).isEqualTo("PUBLISHED");
        assertThat(countRows("ledger_transactions")).isZero();
        assertThat(countRows("ledger_entries")).isZero();

        await().during(Duration.ofMillis(500))
                .atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> {
                    assertThat(postingApi.paymentAttemptCount(paymentPublicId))
                            .isEqualTo(MAX_ATTEMPTS);
                    assertThat(queueMessageCount(properties.topology().ledgerQueue()))
                            .isZero();
                });
    }

    @Test
    void malformedEventShouldBeRetriedBoundedlyThenDeadLettered() {
        MessageProperties metadata = new MessageProperties();
        metadata.setMessageId("ievt_retry_malformed");
        metadata.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        metadata.setContentEncoding(StandardCharsets.UTF_8.name());
        metadata.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        Message malformed = new Message("{not-json".getBytes(StandardCharsets.UTF_8), metadata);

        rabbitTemplate.send(
                properties.topology().exchange(),
                PaymentSucceededEventV1.EVENT_TYPE,
                malformed
        );

        Message deadLetter = requireDeadLetter();
        assertThat(deadLetter.getMessageProperties().getMessageId())
                .isEqualTo("ievt_retry_malformed");
        assertThat(deadLetter.getMessageProperties().getHeaders()).containsKey("x-death");
        assertThat(postingApi.totalPaymentAttempts()).isZero();
        assertThat(countRows("ledger_transactions")).isZero();
        assertThat(queueMessageCount(properties.topology().ledgerQueue())).isZero();
    }

    @Test
    void contradictoryDuplicateShouldRetryThenDeadLetterWithoutChangingLedger() {
        String paymentPublicId = "pi_retry_conflict";
        publish(paymentEnvelope("ievt_retry_conflict_original", paymentPublicId, 300_000L));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(countRows("ledger_transactions")).isOne();
            assertThat(postingApi.paymentAttemptCount(paymentPublicId)).isOne();
        });

        publish(paymentEnvelope("ievt_retry_conflict_changed", paymentPublicId, 300_001L));

        Message deadLetter = requireDeadLetter();
        assertThat(deadLetter.getMessageProperties().getMessageId())
                .isEqualTo("ievt_retry_conflict_changed");
        assertThat(postingApi.paymentAttemptCount(paymentPublicId))
                .isEqualTo(1 + MAX_ATTEMPTS);
        assertThat(countRows("ledger_transactions")).isOne();
        assertThat(countRows("ledger_entries")).isEqualTo(2L);
    }

    @Test
    void equivalentDuplicateShouldAckWithoutRetryOrDeadLetter() {
        String paymentPublicId = "pi_retry_equivalent";
        publish(paymentEnvelope("ievt_retry_equivalent_first", paymentPublicId, 450_000L));
        publish(paymentEnvelope("ievt_retry_equivalent_second", paymentPublicId, 450_000L));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(postingApi.paymentAttemptCount(paymentPublicId)).isEqualTo(2);
            assertThat(postingApi.outcomes()).containsExactly(
                    LedgerPostingOutcome.CREATED,
                    LedgerPostingOutcome.ALREADY_POSTED
            );
            assertThat(countRows("ledger_transactions")).isOne();
            assertThat(countRows("ledger_entries")).isEqualTo(2L);
            assertThat(countUnbalancedTransactions()).isZero();
        });
        await().during(Duration.ofMillis(500))
                .atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> {
                    assertThat(postingApi.paymentAttemptCount(paymentPublicId)).isEqualTo(2);
                    assertThat(queueMessageCount(
                            properties.topology().ledgerDeadLetterQueue()
                    )).isZero();
                });
    }

    @Test
    void successfulEventShouldBeConsumedOnceWithoutRetry() {
        String paymentPublicId = "pi_retry_success";

        publish(paymentEnvelope("ievt_retry_success", paymentPublicId, 525_000L));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(postingApi.paymentAttemptCount(paymentPublicId)).isOne();
            assertThat(postingApi.outcomes()).containsExactly(LedgerPostingOutcome.CREATED);
            assertThat(countRows("ledger_transactions")).isOne();
        });
        assertThat(queueMessageCount(properties.topology().ledgerDeadLetterQueue())).isZero();
    }

    @Test
    void databaseFailureBeforeCommitShouldRollBackEveryLedgerRowAndDeadLetter() {
        String eventId = "ievt_retry_database_rollback";
        String paymentPublicId = "pi_retry_database_rollback";
        installSecondEntryFailureTrigger();

        publish(paymentEnvelope(eventId, paymentPublicId, 625_000L));

        Message deadLetter = requireDeadLetter();
        assertThat(deadLetter.getMessageProperties().getMessageId()).isEqualTo(eventId);
        assertThat(postingApi.paymentAttemptCount(paymentPublicId))
                .isEqualTo(MAX_ATTEMPTS);
        assertThat(postingApi.transactionStates()).containsOnly(true);
        assertThat(countRows("ledger_accounts")).isZero();
        assertThat(countRows("ledger_transactions")).isZero();
        assertThat(countRows("ledger_entries")).isZero();
    }

    private IntegrationEventEnvelope paymentEnvelope(
            String eventId,
            String paymentPublicId,
            long amountMinor
    ) {
        PaymentSucceededEventV1 event = new PaymentSucceededEventV1(
                15L,
                paymentPublicId,
                amountMinor,
                "VND",
                OCCURRED_AT
        );
        return new IntegrationEventEnvelope(
                eventId,
                event.eventType(),
                event.aggregateType(),
                event.aggregateId(),
                event.occurredAt(),
                objectMapper.valueToTree(event)
        );
    }

    private void publish(IntegrationEventEnvelope envelope) {
        IntegrationEventPublicationResult result = transportPublisher.publish(envelope);
        assertThat(result.confirmed()).isTrue();
    }

    private void insertPublishedOutbox(IntegrationEventEnvelope envelope) {
        OffsetDateTime timestamp = envelope.occurredAt().atOffset(ZoneOffset.UTC);
        jdbcTemplate.update("""
                INSERT INTO outbox_events (
                    event_id, aggregate_type, aggregate_id, event_type, payload,
                    status, occurred_at, available_at, published_at, created_at
                )
                VALUES (?, ?, ?, ?, CAST(? AS jsonb), 'PUBLISHED', ?, ?, ?, ?)
                """,
                envelope.eventId(),
                envelope.aggregateType(),
                envelope.aggregateId(),
                envelope.eventType(),
                envelope.payload().toString(),
                timestamp,
                timestamp,
                timestamp,
                timestamp
        );
    }

    private Message requireDeadLetter() {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(queueMessageCount(
                        properties.topology().ledgerDeadLetterQueue()
                )).isOne()
        );
        Message deadLetter = receive(
                properties.topology().ledgerDeadLetterQueue(),
                2_000L
        );
        assertThat(deadLetter).isNotNull();
        return deadLetter;
    }

    private Message receive(String queue, long timeoutMillis) {
        return rabbitTemplate.receive(queue, timeoutMillis);
    }

    private int queueMessageCount(String queue) {
        var queueProperties = rabbitAdmin.getQueueProperties(queue);
        assertThat(queueProperties).isNotNull();
        return ((Number) queueProperties.get(RabbitAdmin.QUEUE_MESSAGE_COUNT)).intValue();
    }

    private String outboxStatus(String eventId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM outbox_events WHERE event_id = ?",
                String.class,
                eventId
        );
    }

    private long countRows(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private long countUnbalancedTransactions() {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM (
                    SELECT ledger_transaction.id
                    FROM ledger_transactions ledger_transaction
                    JOIN ledger_entries entry
                      ON entry.ledger_transaction_id = ledger_transaction.id
                    GROUP BY ledger_transaction.id
                    HAVING SUM(CASE entry.direction
                               WHEN 'DEBIT' THEN entry.amount_minor
                               ELSE -entry.amount_minor END) <> 0
                ) unbalanced
                """, Long.class);
    }

    private void purgeQueues() {
        rabbitAdmin.purgeQueue(properties.topology().ledgerQueue(), true);
        rabbitAdmin.purgeQueue(properties.topology().ledgerDeadLetterQueue(), true);
    }

    private void installSecondEntryFailureTrigger() {
        jdbcTemplate.execute("""
                CREATE OR REPLACE FUNCTION fail_p6_t11_second_entry() RETURNS trigger AS $$
                BEGIN
                    IF NEW.entry_no = 2 THEN
                        RAISE EXCEPTION 'forced consumer Ledger transaction failure';
                    END IF;
                    RETURN NEW;
                END;
                $$ LANGUAGE plpgsql
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER fail_p6_t11_second_entry_trigger
                BEFORE INSERT ON ledger_entries
                FOR EACH ROW EXECUTE FUNCTION fail_p6_t11_second_entry()
                """);
    }

    private void dropLedgerEntryFailureTrigger() {
        jdbcTemplate.execute("""
                DROP TRIGGER IF EXISTS fail_p6_t11_second_entry_trigger ON ledger_entries
                """);
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS fail_p6_t11_second_entry()");
    }

    private static void assertBoundedBackoff(List<Long> attemptTimes) {
        assertThat(attemptTimes).hasSize(MAX_ATTEMPTS);
        Duration firstDelay = Duration.ofNanos(attemptTimes.get(1) - attemptTimes.get(0));
        Duration secondDelay = Duration.ofNanos(attemptTimes.get(2) - attemptTimes.get(1));
        assertThat(firstDelay).isGreaterThanOrEqualTo(Duration.ofMillis(75));
        assertThat(secondDelay).isGreaterThanOrEqualTo(Duration.ofMillis(150));
        assertThat(Duration.ofNanos(attemptTimes.get(2) - attemptTimes.get(0)))
                .isLessThan(Duration.ofSeconds(5));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FaultInjectionConfiguration {

        @Bean
        @Primary
        FaultInjectingLedgerPostingApi faultInjectingLedgerPostingApi(
                LedgerPostingService delegate
        ) {
            return new FaultInjectingLedgerPostingApi(delegate);
        }
    }

    static final class FaultInjectingLedgerPostingApi implements LedgerPostingApi {

        private final LedgerPostingService delegate;
        private final Map<String, AtomicInteger> remainingPaymentFailures =
                new ConcurrentHashMap<>();
        private final Map<String, CopyOnWriteArrayList<Long>> paymentAttemptTimes =
                new ConcurrentHashMap<>();
        private final List<LedgerPostingOutcome> outcomes = new CopyOnWriteArrayList<>();
        private final List<Boolean> transactionStates = new CopyOnWriteArrayList<>();

        private FaultInjectingLedgerPostingApi(LedgerPostingService delegate) {
            this.delegate = delegate;
        }

        @Override
        public LedgerPostingResult postPaymentSucceeded(
                PostPaymentSucceededCommand command
        ) {
            String reference = command.paymentPublicId();
            paymentAttemptTimes.computeIfAbsent(
                    reference,
                    ignored -> new CopyOnWriteArrayList<>()
            ).add(System.nanoTime());
            transactionStates.add(
                    TransactionSynchronizationManager.isActualTransactionActive()
            );
            AtomicInteger failures = remainingPaymentFailures.get(reference);
            if (failures != null && failures.getAndDecrement() > 0) {
                throw new IllegalStateException("forced transient Ledger failure");
            }
            LedgerPostingResult result = delegate.postPaymentSucceeded(command);
            outcomes.add(result.outcome());
            return result;
        }

        @Override
        public LedgerPostingResult postRefundSucceeded(
                PostRefundSucceededCommand command
        ) {
            return delegate.postRefundSucceeded(command);
        }

        void failNextPaymentAttempts(String paymentPublicId, int failureCount) {
            remainingPaymentFailures.put(
                    paymentPublicId,
                    new AtomicInteger(failureCount)
            );
        }

        int paymentAttemptCount(String paymentPublicId) {
            return paymentAttemptTimes(paymentPublicId).size();
        }

        int totalPaymentAttempts() {
            return paymentAttemptTimes.values().stream().mapToInt(List::size).sum();
        }

        List<Long> paymentAttemptTimes(String paymentPublicId) {
            return List.copyOf(paymentAttemptTimes.getOrDefault(
                    paymentPublicId,
                    new CopyOnWriteArrayList<>()
            ));
        }

        List<LedgerPostingOutcome> outcomes() {
            return List.copyOf(outcomes);
        }

        List<Boolean> transactionStates() {
            return List.copyOf(transactionStates);
        }

        void reset() {
            remainingPaymentFailures.clear();
            paymentAttemptTimes.clear();
            outcomes.clear();
            transactionStates.clear();
        }
    }
}
