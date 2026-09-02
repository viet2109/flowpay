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
import com.flowpay.backend.refund.application.event.RefundSucceededEventV1;
import com.flowpay.backend.testing.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
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

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Currency;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@ActiveProfiles("test")
@Import(LedgerIntegrationEventConsumerIntegrationTest.RecordingConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LedgerIntegrationEventConsumerIntegrationTest extends PostgresIntegrationTest {

    private static final Instant PAYMENT_OCCURRED_AT =
            Instant.parse("2026-09-02T09:00:00Z");
    private static final Instant REFUND_OCCURRED_AT =
            Instant.parse("2026-09-02T09:05:00Z");
    private static final Currency VND = Currency.getInstance("VND");

    @Container
    private static final RabbitMQContainer RABBITMQ =
            new RabbitMQContainer("rabbitmq:4.3-management-alpine");

    @Autowired
    private IntegrationEventTransportPublisher transportPublisher;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RecordingLedgerPostingApi recordingPostingApi;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    @Autowired
    private FlowPayMessagingProperties properties;

    @DynamicPropertySource
    static void messagingProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.host", RABBITMQ::getHost);
        registry.add("spring.rabbitmq.port", RABBITMQ::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBITMQ::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBITMQ::getAdminPassword);
        registry.add("flowpay.messaging.ledger-consumer.enabled", () -> "true");
        registry.add(
                "flowpay.messaging.outbox.publisher-confirm-timeout",
                () -> "2s"
        );
    }

    @BeforeEach
    void cleanState() {
        recordingPostingApi.reset();
        jdbcTemplate.update("""
                TRUNCATE TABLE ledger_entries, ledger_transactions, ledger_accounts
                RESTART IDENTITY CASCADE
                """);
        rabbitAdmin.purgeQueue(properties.topology().ledgerQueue(), true);
        rabbitAdmin.purgeQueue(properties.topology().ledgerDeadLetterQueue(), true);
    }

    @AfterEach
    void cleanQueues() {
        rabbitAdmin.purgeQueue(properties.topology().ledgerQueue(), true);
        rabbitAdmin.purgeQueue(properties.topology().ledgerDeadLetterQueue(), true);
    }

    @Test
    void shouldPostPaymentTransactionallyAndAcceptIdenticalDuplicate() {
        PaymentSucceededEventV1 event = new PaymentSucceededEventV1(
                15L,
                "pi_consumer_payment",
                1_000_000L,
                "VND",
                PAYMENT_OCCURRED_AT
        );
        IntegrationEventEnvelope envelope = envelope(
                "ievt_consumer_payment",
                event.eventType(),
                event.aggregateType(),
                event.aggregateId(),
                event.occurredAt(),
                event
        );

        publish(envelope);
        publish(envelope);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(recordingPostingApi.paymentCommands()).containsExactly(
                    new PostPaymentSucceededCommand(
                            15L,
                            "pi_consumer_payment",
                            new Money(1_000_000L, VND),
                            PAYMENT_OCCURRED_AT
                    ),
                    new PostPaymentSucceededCommand(
                            15L,
                            "pi_consumer_payment",
                            new Money(1_000_000L, VND),
                            PAYMENT_OCCURRED_AT
                    )
            );
            assertThat(recordingPostingApi.outcomes()).containsExactly(
                    LedgerPostingOutcome.CREATED,
                    LedgerPostingOutcome.ALREADY_POSTED
            );
            assertThat(countRows("ledger_transactions")).isEqualTo(1L);
            assertThat(countRows("ledger_entries")).isEqualTo(2L);
        });

        assertThat(recordingPostingApi.transactionStates()).containsOnly(true);
        assertPosting(
                "PAYMENT_SUCCEEDED",
                "PAYMENT_INTENT",
                "pi_consumer_payment",
                PAYMENT_OCCURRED_AT
        );
        assertThat(entryFacts("pi_consumer_payment")).containsExactly(
                "SYSTEM_CLEARING:VND:DEBIT:1000000",
                "MERCHANT_PAYABLE:15:VND:CREDIT:1000000"
        );
    }

    @Test
    void shouldPostRefundTransactionallyAndAcceptIdenticalDuplicate() {
        RefundSucceededEventV1 event = new RefundSucceededEventV1(
                29L,
                "re_consumer_refund",
                "pi_refunded_payment",
                300_000L,
                "VND",
                REFUND_OCCURRED_AT
        );
        IntegrationEventEnvelope envelope = envelope(
                "ievt_consumer_refund",
                event.eventType(),
                event.aggregateType(),
                event.aggregateId(),
                event.occurredAt(),
                event
        );

        publish(envelope);
        publish(envelope);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(recordingPostingApi.refundCommands()).containsExactly(
                    new PostRefundSucceededCommand(
                            29L,
                            "re_consumer_refund",
                            new Money(300_000L, VND),
                            REFUND_OCCURRED_AT
                    ),
                    new PostRefundSucceededCommand(
                            29L,
                            "re_consumer_refund",
                            new Money(300_000L, VND),
                            REFUND_OCCURRED_AT
                    )
            );
            assertThat(recordingPostingApi.outcomes()).containsExactly(
                    LedgerPostingOutcome.CREATED,
                    LedgerPostingOutcome.ALREADY_POSTED
            );
            assertThat(countRows("ledger_transactions")).isEqualTo(1L);
            assertThat(countRows("ledger_entries")).isEqualTo(2L);
        });

        assertThat(recordingPostingApi.transactionStates()).containsOnly(true);
        assertPosting(
                "REFUND_SUCCEEDED",
                "REFUND",
                "re_consumer_refund",
                REFUND_OCCURRED_AT
        );
        assertThat(entryFacts("re_consumer_refund")).containsExactly(
                "MERCHANT_PAYABLE:29:VND:DEBIT:300000",
                "SYSTEM_CLEARING:VND:CREDIT:300000"
        );
    }

    private IntegrationEventEnvelope envelope(
            String eventId,
            String eventType,
            String aggregateType,
            String aggregateId,
            Instant occurredAt,
            Object payload
    ) {
        return new IntegrationEventEnvelope(
                eventId,
                eventType,
                aggregateType,
                aggregateId,
                occurredAt,
                objectMapper.valueToTree(payload)
        );
    }

    private void publish(IntegrationEventEnvelope envelope) {
        IntegrationEventPublicationResult result = transportPublisher.publish(envelope);
        assertThat(result.confirmed()).isTrue();
    }

    private void assertPosting(
            String postingType,
            String referenceType,
            String referenceId,
            Instant occurredAt
    ) {
        PostingRow row = jdbcTemplate.queryForObject(
                """
                        SELECT posting_type, reference_type, reference_id,
                               TRIM(currency) AS currency, occurred_at
                        FROM ledger_transactions
                        WHERE reference_id = ?
                        """,
                (resultSet, rowNumber) -> new PostingRow(
                        resultSet.getString("posting_type"),
                        resultSet.getString("reference_type"),
                        resultSet.getString("reference_id"),
                        resultSet.getString("currency"),
                        resultSet.getObject("occurred_at", OffsetDateTime.class).toInstant()
                ),
                referenceId
        );
        assertThat(row).isEqualTo(new PostingRow(
                postingType,
                referenceType,
                referenceId,
                "VND",
                occurredAt
        ));
    }

    private List<String> entryFacts(String referenceId) {
        return jdbcTemplate.queryForList(
                """
                        SELECT account.account_code || ':' || entry.direction || ':'
                                   || entry.amount_minor AS fact
                        FROM ledger_entries entry
                        JOIN ledger_transactions ledger_tx
                          ON ledger_tx.id = entry.ledger_transaction_id
                        JOIN ledger_accounts account
                          ON account.id = entry.ledger_account_id
                        WHERE ledger_tx.reference_id = ?
                        ORDER BY entry.entry_no
                        """,
                String.class,
                referenceId
        );
    }

    private long countRows(String table) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table,
                Long.class
        );
        return count == null ? 0L : count;
    }

    private record PostingRow(
            String postingType,
            String referenceType,
            String referenceId,
            String currency,
            Instant occurredAt
    ) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RecordingConfiguration {

        @Bean
        @Primary
        RecordingLedgerPostingApi recordingLedgerPostingApi(
                LedgerPostingService delegate
        ) {
            return new RecordingLedgerPostingApi(delegate);
        }
    }

    static final class RecordingLedgerPostingApi implements LedgerPostingApi {

        private final LedgerPostingService delegate;
        private final List<PostPaymentSucceededCommand> paymentCommands =
                new CopyOnWriteArrayList<>();
        private final List<PostRefundSucceededCommand> refundCommands =
                new CopyOnWriteArrayList<>();
        private final List<LedgerPostingOutcome> outcomes =
                new CopyOnWriteArrayList<>();
        private final List<Boolean> transactionStates =
                new CopyOnWriteArrayList<>();

        private RecordingLedgerPostingApi(LedgerPostingService delegate) {
            this.delegate = delegate;
        }

        @Override
        public LedgerPostingResult postPaymentSucceeded(
                PostPaymentSucceededCommand command
        ) {
            paymentCommands.add(command);
            recordTransactionState();
            return record(delegate.postPaymentSucceeded(command));
        }

        @Override
        public LedgerPostingResult postRefundSucceeded(
                PostRefundSucceededCommand command
        ) {
            refundCommands.add(command);
            recordTransactionState();
            return record(delegate.postRefundSucceeded(command));
        }

        List<PostPaymentSucceededCommand> paymentCommands() {
            return List.copyOf(paymentCommands);
        }

        List<PostRefundSucceededCommand> refundCommands() {
            return List.copyOf(refundCommands);
        }

        List<LedgerPostingOutcome> outcomes() {
            return List.copyOf(outcomes);
        }

        List<Boolean> transactionStates() {
            return List.copyOf(transactionStates);
        }

        void reset() {
            paymentCommands.clear();
            refundCommands.clear();
            outcomes.clear();
            transactionStates.clear();
        }

        private LedgerPostingResult record(LedgerPostingResult result) {
            outcomes.add(result.outcome());
            return result;
        }

        private void recordTransactionState() {
            transactionStates.add(
                    TransactionSynchronizationManager.isActualTransactionActive()
            );
        }
    }
}
