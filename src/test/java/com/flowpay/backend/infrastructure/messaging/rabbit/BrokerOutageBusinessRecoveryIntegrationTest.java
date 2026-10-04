package com.flowpay.backend.infrastructure.messaging.rabbit;

import com.flowpay.backend.infrastructure.messaging.FlowPayMessagingProperties;
import com.flowpay.backend.infrastructure.messaging.IntegrationEventPublicationResult;
import com.flowpay.backend.infrastructure.messaging.IntegrationEventTransportPublisher;
import com.flowpay.backend.infrastructure.messaging.outbox.IntegrationEventEnvelope;
import com.flowpay.backend.infrastructure.messaging.outbox.relay.OutboxRelayBatchResult;
import com.flowpay.backend.infrastructure.messaging.outbox.relay.OutboxRelayService;
import com.flowpay.backend.merchant.application.ApiKeyRepository;
import com.flowpay.backend.merchant.application.ApiKeySecretCodec;
import com.flowpay.backend.merchant.application.GeneratedApiKeySecret;
import com.flowpay.backend.merchant.application.MerchantRepository;
import com.flowpay.backend.merchant.domain.ApiKey;
import com.flowpay.backend.merchant.domain.Merchant;
import com.flowpay.backend.payment.application.PaymentProviderPort;
import com.flowpay.backend.payment.application.PaymentProviderRequest;
import com.flowpay.backend.payment.application.PaymentProviderResult;
import com.flowpay.backend.payment.application.event.PaymentSucceededEventV1;
import com.flowpay.backend.payment.domain.ProviderOutcome;
import com.flowpay.backend.refund.application.RefundProviderPort;
import com.flowpay.backend.refund.application.RefundProviderRequest;
import com.flowpay.backend.refund.application.RefundProviderResult;
import com.flowpay.backend.refund.domain.RefundProviderOutcome;
import com.flowpay.backend.testing.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(BrokerOutageBusinessRecoveryIntegrationTest.FailureTestConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BrokerOutageBusinessRecoveryIntegrationTest extends PostgresIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-09-02T12:00:00Z");
    private static final String PAYMENT_PATH = "/api/v1/payment-intents";
    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    @Container
    private static final RabbitMQContainer RABBITMQ =
            new RabbitMQContainer("rabbitmq:4.3-management-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MerchantRepository merchantRepository;

    @Autowired
    private ApiKeyRepository apiKeyRepository;

    @Autowired
    private ApiKeySecretCodec secretCodec;

    @Autowired
    private OutboxRelayService relayService;

    @Autowired
    private MutableClock clock;

    @Autowired
    private CountingPaymentProvider paymentProvider;

    @Autowired
    private CountingRefundProvider refundProvider;

    @Autowired
    private ControlledConfirmedPublisher controlledPublisher;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    @Autowired
    private CachingConnectionFactory connectionFactory;

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
                () -> "500ms"
        );
        registry.add("flowpay.messaging.outbox.relay.initial-backoff", () -> "100ms");
        registry.add("flowpay.messaging.outbox.relay.max-backoff", () -> "1s");
    }

    @BeforeEach
    void cleanState() throws Exception {
        ensureRabbitRunning();
        clock.set(NOW);
        paymentProvider.reset();
        refundProvider.reset();
        controlledPublisher.reset();
        jdbcTemplate.update("""
                TRUNCATE TABLE outbox_events, ledger_entries, ledger_transactions,
                    ledger_accounts, refunds, idempotency_records, payment_transactions,
                    payment_intents, refresh_tokens, merchant_api_keys, merchant_members,
                    merchants, users RESTART IDENTITY CASCADE
                """);
        purgeQueues();
    }

    @AfterEach
    void restoreBroker() throws Exception {
        ensureRabbitRunning();
        purgeQueues();
    }

    @Test
    void brokerOutageShouldNotRollBackHttpSuccessesAndRecoveryShouldPostBoth()
            throws Exception {
        StoredKey key = createStoredKey();
        stopRabbitApplication();

        String paymentId = createPayment(key);
        confirmPayment(key, paymentId);
        String refundId = createRefund(key, paymentId);

        assertThat(paymentStatus(paymentId)).isEqualTo("PARTIALLY_REFUNDED");
        assertThat(refundStatus(refundId)).isEqualTo("SUCCEEDED");
        assertThat(paymentProvider.invocationCount()).isOne();
        assertThat(refundProvider.invocationCount()).isOne();
        assertThat(countRows("outbox_events")).isEqualTo(4L);
        assertThat(countOutboxStatus("PENDING")).isEqualTo(4L);
        assertThat(countRows("ledger_transactions")).isZero();

        OutboxRelayBatchResult failed = relayService.relayDueEvents();

        assertThat(failed).isEqualTo(new OutboxRelayBatchResult(4, 0, 4));
        assertThat(countOutboxStatus("FAILED")).isEqualTo(4L);
        assertThat(paymentStatus(paymentId)).isEqualTo("PARTIALLY_REFUNDED");
        assertThat(refundStatus(refundId)).isEqualTo("SUCCEEDED");

        ensureRabbitRunning();
        clock.advance(Duration.ofSeconds(2));
        OutboxRelayBatchResult recovered = relayService.relayDueEvents();

        // Webhook routes processing events independently; Ledger still receives only successes.
        assertThat(recovered).isEqualTo(new OutboxRelayBatchResult(4, 4, 0));
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(countOutboxStatus("PUBLISHED")).isEqualTo(4L);
            assertThat(countRows("ledger_transactions")).isEqualTo(2L);
            assertThat(countRows("ledger_entries")).isEqualTo(4L);
        });
        assertThat(countOutboxStatus("FAILED")).isZero();
        assertThat(paymentProvider.invocationCount()).isOne();
        assertThat(refundProvider.invocationCount()).isOne();
        assertThat(countRows("payment_intents")).isOne();
        assertThat(countRows("payment_transactions")).isOne();
        assertThat(countRows("refunds")).isOne();
        assertThat(countBalancedPostings()).isEqualTo(2L);
        assertThat(queueMessageCount(properties.topology().ledgerDeadLetterQueue()))
                .isZero();
    }

    @ParameterizedTest(name = "{0} concurrent relay workers")
    @ValueSource(ints = {2, 6})
    void concurrentRelayDuplicatesShouldProduceOneBalancedLedgerPosting(
            int workerCount
    ) throws Exception {
        String eventId = "ievt_relay_concurrent_" + workerCount;
        String paymentId = "pi_relay_concurrent_" + workerCount;
        insertPendingPaymentEvent(eventId, paymentId);
        controlledPublisher.block(eventId, workerCount);
        ExecutorService executor = Executors.newFixedThreadPool(workerCount);
        try {
            List<Future<OutboxRelayBatchResult>> attempts = java.util.stream.IntStream
                    .range(0, workerCount)
                    .mapToObj(ignored -> executor.submit(relayService::relayDueEvents))
                    .toList();
            assertThat(controlledPublisher.awaitBlockedPublishers()).isTrue();
            controlledPublisher.releaseBlockedPublishers();

            for (Future<OutboxRelayBatchResult> attempt : attempts) {
                assertThat(attempt.get(20, TimeUnit.SECONDS))
                        .isEqualTo(new OutboxRelayBatchResult(1, 1, 0));
            }
        } finally {
            controlledPublisher.releaseBlockedPublishers();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(countOutboxStatus("PUBLISHED")).isOne();
            assertThat(countRows("ledger_transactions")).isOne();
            assertThat(countRows("ledger_entries")).isEqualTo(2L);
        });
        assertThat(controlledPublisher.eventIds())
                .filteredOn(eventId::equals)
                .hasSize(workerCount);
        assertThat(countBalancedPostings()).isOne();
        assertThat(queueMessageCount(properties.topology().ledgerDeadLetterQueue()))
                .isZero();
    }

    private String createPayment(StoredKey key) throws Exception {
        MvcResult result = mockMvc.perform(post(PAYMENT_PATH)
                        .header(HttpHeaders.AUTHORIZATION, bearer(key.rawKey()))
                        .header(IDEMPOTENCY_KEY, "outage-payment-create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amount": 1000000,
                                  "currency": "VND",
                                  "orderId": "ORDER-OUTAGE",
                                  "description": "Broker outage payment"
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        return responseId(result);
    }

    private void confirmPayment(StoredKey key, String paymentId) throws Exception {
        mockMvc.perform(post(PAYMENT_PATH + "/" + paymentId + "/confirm")
                        .header(HttpHeaders.AUTHORIZATION, bearer(key.rawKey()))
                        .header(IDEMPOTENCY_KEY, "outage-payment-confirm"))
                .andExpect(status().isOk());
    }

    private String createRefund(StoredKey key, String paymentId) throws Exception {
        MvcResult result = mockMvc.perform(post(
                        PAYMENT_PATH + "/" + paymentId + "/refunds"
                )
                        .header(HttpHeaders.AUTHORIZATION, bearer(key.rawKey()))
                        .header(IDEMPOTENCY_KEY, "outage-refund-create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amount": 300000,
                                  "reason": "Customer request"
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        return responseId(result);
    }

    private String responseId(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray())
                .path("data")
                .path("id")
                .stringValue();
    }

    private StoredKey createStoredKey() {
        Merchant merchant = merchantRepository.save(Merchant.create(
                "mrc_broker_outage",
                "Broker Outage Store",
                NOW
        ));
        GeneratedApiKeySecret secret = secretCodec.generate();
        apiKeyRepository.save(ApiKey.create(
                "key_broker_outage",
                merchant.id(),
                "Broker outage key",
                secret.prefix(),
                secret.digest(),
                null,
                NOW
        ));
        return new StoredKey(secret.rawKey());
    }

    private String paymentStatus(String paymentId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM payment_intents WHERE public_id = ?",
                String.class,
                paymentId
        );
    }

    private String refundStatus(String refundId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM refunds WHERE public_id = ?",
                String.class,
                refundId
        );
    }

    private long countOutboxStatus(String status) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE status = ?",
                Long.class,
                status
        );
    }

    private long countBalancedPostings() {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM (
                    SELECT ledger_transaction.id
                    FROM ledger_transactions ledger_transaction
                    JOIN ledger_entries entry
                      ON entry.ledger_transaction_id = ledger_transaction.id
                    GROUP BY ledger_transaction.id
                    HAVING COUNT(*) = 2
                       AND SUM(CASE entry.direction
                               WHEN 'DEBIT' THEN entry.amount_minor
                               ELSE -entry.amount_minor END) = 0
                ) balanced
                """, Long.class);
    }

    private void insertPendingPaymentEvent(String eventId, String paymentId) {
        jdbcTemplate.update("""
                INSERT INTO outbox_events (
                    event_id, aggregate_type, aggregate_id, event_type, payload,
                    status, occurred_at, available_at, created_at
                )
                VALUES (?, 'PAYMENT_INTENT', ?, ?, CAST(? AS jsonb), 'PENDING', ?, ?, ?)
                """,
                eventId,
                paymentId,
                PaymentSucceededEventV1.EVENT_TYPE,
                """
                        {"merchantInternalId":77,"paymentPublicId":"%s",
                         "amountMinor":900000,"currency":"VND",
                         "occurredAt":"%s"}
                        """.formatted(paymentId, NOW),
                NOW.atOffset(ZoneOffset.UTC),
                NOW.atOffset(ZoneOffset.UTC),
                NOW.atOffset(ZoneOffset.UTC)
        );
    }

    private long countRows(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private int queueMessageCount(String queue) {
        var queueProperties = rabbitAdmin.getQueueProperties(queue);
        assertThat(queueProperties).isNotNull();
        return ((Number) queueProperties.get(RabbitAdmin.QUEUE_MESSAGE_COUNT)).intValue();
    }

    private void stopRabbitApplication() throws Exception {
        org.testcontainers.containers.Container.ExecResult result =
                RABBITMQ.execInContainer("rabbitmqctl", "stop_app");
        assertThat(result.getExitCode()).isZero();
        connectionFactory.resetConnection();
    }

    private void ensureRabbitRunning() throws Exception {
        org.testcontainers.containers.Container.ExecResult result =
                RABBITMQ.execInContainer("rabbitmqctl", "start_app");
        assertThat(result.getExitCode()).isZero();
        connectionFactory.resetConnection();
        RuntimeException lastFailure = null;
        for (int attempt = 0; attempt < 50; attempt++) {
            try {
                rabbitAdmin.initialize();
                if (rabbitAdmin.getQueueProperties(
                        properties.topology().ledgerQueue()
                ) != null) {
                    return;
                }
            } catch (RuntimeException exception) {
                lastFailure = exception;
            }
            Thread.sleep(100L);
        }
        throw new IllegalStateException("RabbitMQ did not recover in time", lastFailure);
    }

    private void purgeQueues() {
        rabbitAdmin.purgeQueue(properties.topology().ledgerQueue(), true);
        rabbitAdmin.purgeQueue(properties.topology().ledgerDeadLetterQueue(), true);
    }

    private static String bearer(String rawKey) {
        return "Bearer " + rawKey;
    }

    private record StoredKey(String rawKey) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FailureTestConfiguration {

        @Bean
        @Primary
        MutableClock brokerOutageClock() {
            return new MutableClock(NOW);
        }

        @Bean
        @Primary
        CountingPaymentProvider countingPaymentProvider() {
            return new CountingPaymentProvider();
        }

        @Bean
        @Primary
        CountingRefundProvider countingRefundProvider() {
            return new CountingRefundProvider();
        }

        @Bean
        @Primary
        ControlledConfirmedPublisher controlledConfirmedPublisher(
                ConfirmedRabbitIntegrationEventPublisher delegate
        ) {
            return new ControlledConfirmedPublisher(delegate);
        }
    }

    static final class ControlledConfirmedPublisher
            implements IntegrationEventTransportPublisher {

        private final ConfirmedRabbitIntegrationEventPublisher delegate;
        private final java.util.concurrent.CopyOnWriteArrayList<String> eventIds =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        private final AtomicReference<BlockingPublication> blockingPublication =
                new AtomicReference<>();

        private ControlledConfirmedPublisher(
                ConfirmedRabbitIntegrationEventPublisher delegate
        ) {
            this.delegate = delegate;
        }

        @Override
        public IntegrationEventPublicationResult publish(
                IntegrationEventEnvelope envelope
        ) {
            eventIds.add(envelope.eventId());
            awaitReleaseIfBlocked(envelope.eventId());
            return delegate.publish(envelope);
        }

        void block(String eventId, int publisherCount) {
            blockingPublication.set(new BlockingPublication(
                    eventId,
                    new CountDownLatch(publisherCount),
                    new CountDownLatch(1)
            ));
        }

        boolean awaitBlockedPublishers() throws InterruptedException {
            BlockingPublication blocking = blockingPublication.get();
            return blocking != null && blocking.ready().await(10, TimeUnit.SECONDS);
        }

        void releaseBlockedPublishers() {
            BlockingPublication blocking = blockingPublication.get();
            if (blocking != null) {
                blocking.release().countDown();
            }
        }

        List<String> eventIds() {
            return List.copyOf(eventIds);
        }

        void reset() {
            releaseBlockedPublishers();
            eventIds.clear();
            blockingPublication.set(null);
        }

        private void awaitReleaseIfBlocked(String eventId) {
            BlockingPublication blocking = blockingPublication.get();
            if (blocking == null || !blocking.eventId().equals(eventId)) {
                return;
            }
            blocking.ready().countDown();
            try {
                if (!blocking.release().await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Concurrent publishers were not released");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Concurrent publisher was interrupted", exception);
            }
        }

        private record BlockingPublication(
                String eventId,
                CountDownLatch ready,
                CountDownLatch release
        ) {
        }
    }

    static final class CountingPaymentProvider implements PaymentProviderPort {

        private final AtomicInteger invocations = new AtomicInteger();

        @Override
        public PaymentProviderResult charge(PaymentProviderRequest request) {
            invocations.incrementAndGet();
            return new PaymentProviderResult(
                    "SIMULATOR",
                    ProviderOutcome.SUCCESS,
                    "sim_" + request.paymentPublicReference(),
                    null,
                    null
            );
        }

        int invocationCount() {
            return invocations.get();
        }

        void reset() {
            invocations.set(0);
        }
    }

    static final class CountingRefundProvider implements RefundProviderPort {

        private final AtomicInteger invocations = new AtomicInteger();

        @Override
        public RefundProviderResult refund(RefundProviderRequest request) {
            invocations.incrementAndGet();
            return new RefundProviderResult(
                    "SIMULATOR",
                    RefundProviderOutcome.SUCCESS,
                    "sim_" + request.refundPublicId(),
                    null,
                    null
            );
        }

        int invocationCount() {
            return invocations.get();
        }

        void reset() {
            invocations.set(0);
        }
    }

    static final class MutableClock extends Clock {

        private final AtomicReference<Instant> current;

        private MutableClock(Instant initialTime) {
            current = new AtomicReference<>(initialTime);
        }

        void set(Instant instant) {
            current.set(instant);
        }

        void advance(Duration duration) {
            current.updateAndGet(instant -> instant.plus(duration));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new IllegalArgumentException("only UTC is supported in this test");
            }
            return this;
        }

        @Override
        public Instant instant() {
            return current.get();
        }
    }
}
