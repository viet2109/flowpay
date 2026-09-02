package com.flowpay.backend.infrastructure.messaging.rabbit;

import com.flowpay.backend.infrastructure.messaging.FlowPayMessagingProperties;
import com.flowpay.backend.infrastructure.messaging.IntegrationEventPublicationResult;
import com.flowpay.backend.infrastructure.messaging.IntegrationEventTransportPublisher;
import com.flowpay.backend.infrastructure.messaging.outbox.IntegrationEventEnvelope;
import com.flowpay.backend.infrastructure.messaging.outbox.OutboxEvent;
import com.flowpay.backend.infrastructure.messaging.outbox.OutboxRepository;
import com.flowpay.backend.infrastructure.messaging.outbox.relay.OutboxRelayBatchResult;
import com.flowpay.backend.infrastructure.messaging.outbox.relay.OutboxRelayFailureSummary;
import com.flowpay.backend.infrastructure.messaging.outbox.relay.OutboxRelayService;
import com.flowpay.backend.payment.application.event.PaymentSucceededEventV1;
import com.flowpay.backend.testing.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.rabbitmq.RabbitMQContainer;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(OutboxRelayIntegrationTest.RelayTestConfiguration.class)
class OutboxRelayIntegrationTest extends PostgresIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-09-02T07:00:00Z");
    private static final Instant OCCURRED_AT = Instant.parse("2026-09-02T06:00:00Z");

    @Container
    private static final RabbitMQContainer RABBITMQ =
            new RabbitMQContainer("rabbitmq:4.3-management-alpine");

    @Autowired
    private OutboxRelayService relayService;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private RecordingPublisher recordingPublisher;

    @Autowired
    private MutableClock clock;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    @Autowired
    private CachingConnectionFactory connectionFactory;

    @Autowired
    private FlowPayMessagingProperties properties;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ApplicationContext applicationContext;

    @DynamicPropertySource
    static void relayProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.host", RABBITMQ::getHost);
        registry.add("spring.rabbitmq.port", RABBITMQ::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBITMQ::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBITMQ::getAdminPassword);
        registry.add(
                "flowpay.messaging.outbox.publisher-confirm-timeout",
                () -> "500ms"
        );
        registry.add("flowpay.messaging.outbox.relay.batch-size", () -> "2");
        registry.add(
                "flowpay.messaging.outbox.relay.initial-backoff",
                () -> "100ms"
        );
        registry.add(
                "flowpay.messaging.outbox.relay.max-backoff",
                () -> "200ms"
        );
    }

    @BeforeEach
    void cleanState() throws Exception {
        ensureRabbitRunning();
        clock.set(NOW);
        recordingPublisher.reset();
        jdbcTemplate.update("TRUNCATE TABLE outbox_events RESTART IDENTITY");
        rabbitAdmin.purgeQueue(properties.topology().ledgerQueue(), true);
        rabbitAdmin.purgeQueue(properties.topology().ledgerDeadLetterQueue(), true);
    }

    @AfterEach
    void restoreBrokerAndCleanState() throws Exception {
        ensureRabbitRunning();
        jdbcTemplate.update("TRUNCATE TABLE outbox_events RESTART IDENTITY");
        rabbitAdmin.purgeQueue(properties.topology().ledgerQueue(), true);
        rabbitAdmin.purgeQueue(properties.topology().ledgerDeadLetterQueue(), true);
    }

    @Test
    void shouldEnforceOrderingBatchIsolationAndKeepBrokerOutsideTransaction() {
        assertThat(applicationContext.containsBean("outboxRelayScheduler"))
                .isFalse();
        save(pending(
                "ievt_relay_unsafe",
                "unsafe.relay.v1",
                NOW.minusSeconds(2)
        ));
        save(pending(
                "ievt_relay_second",
                PaymentSucceededEventV1.EVENT_TYPE,
                NOW.minusSeconds(1)
        ));
        save(pending(
                "ievt_relay_third",
                PaymentSucceededEventV1.EVENT_TYPE,
                NOW
        ));

        OutboxRelayBatchResult firstBatch = relayService.relayDueEvents();

        assertThat(firstBatch).isEqualTo(new OutboxRelayBatchResult(2, 1, 1));
        assertThat(recordingPublisher.eventIds())
                .containsExactly("ievt_relay_unsafe", "ievt_relay_second");
        assertThat(recordingPublisher.transactionStates())
                .containsOnly(false);
        RelayRow unsafe = row("ievt_relay_unsafe");
        assertThat(unsafe.status()).isEqualTo("FAILED");
        assertThat(unsafe.retryCount()).isOne();
        assertThat(unsafe.availableAt()).isEqualTo(NOW.plusMillis(100));
        assertThat(unsafe.lastError())
                .isEqualTo(
                        "Outbox publication failed unexpectedly: IllegalStateException"
                )
                .doesNotContain("password", "Authorization", "amqp://")
                .hasSizeLessThanOrEqualTo(OutboxRelayFailureSummary.MAX_LENGTH);
        assertPublished("ievt_relay_second", NOW);
        assertThat(row("ievt_relay_third").status()).isEqualTo("PENDING");
        assertThat(receiveLedgerMessage().getMessageProperties().getMessageId())
                .isEqualTo("ievt_relay_second");

        OutboxRelayBatchResult secondBatch = relayService.relayDueEvents();

        assertThat(secondBatch).isEqualTo(new OutboxRelayBatchResult(1, 1, 0));
        assertPublished("ievt_relay_third", NOW);
        assertThat(receiveLedgerMessage().getMessageProperties().getMessageId())
                .isEqualTo("ievt_relay_third");
        assertThat(relayService.relayDueEvents())
                .isEqualTo(OutboxRelayBatchResult.empty());
        assertThat(recordingPublisher.eventIds()).hasSize(3);
    }

    @Test
    void shouldRetryIndefinitelyWithCappedBackoffAndNeverSelectPublishedRows() {
        save(pending("ievt_relay_backoff", "unknown.relay.v1", NOW));

        assertThat(relayService.relayDueEvents().failedCount()).isOne();
        assertFailed("ievt_relay_backoff", 1, NOW.plusMillis(100));
        assertThat(relayService.relayDueEvents())
                .isEqualTo(OutboxRelayBatchResult.empty());

        clock.advance(Duration.ofMillis(100));
        assertThat(relayService.relayDueEvents().failedCount()).isOne();
        assertFailed("ievt_relay_backoff", 2, NOW.plusMillis(300));

        clock.advance(Duration.ofMillis(200));
        assertThat(relayService.relayDueEvents().failedCount()).isOne();
        assertFailed("ievt_relay_backoff", 3, NOW.plusMillis(500));

        clock.advance(Duration.ofMillis(200));
        assertThat(relayService.relayDueEvents().failedCount()).isOne();
        assertFailed("ievt_relay_backoff", 4, NOW.plusMillis(700));
    }

    @Test
    void shouldPersistFailureDuringBrokerOutageAndPublishAfterRecovery()
            throws Exception {
        save(pending(
                "ievt_relay_recovery",
                PaymentSucceededEventV1.EVENT_TYPE,
                NOW
        ));
        stopRabbitApplication();
        try {
            OutboxRelayBatchResult failedBatch = relayService.relayDueEvents();

            assertThat(failedBatch.failedCount()).isOne();
            assertFailed("ievt_relay_recovery", 1, NOW.plusMillis(100));
            assertThat(relayService.relayDueEvents())
                    .isEqualTo(OutboxRelayBatchResult.empty());
        } finally {
            ensureRabbitRunning();
        }

        clock.advance(Duration.ofMillis(100));
        OutboxRelayBatchResult recoveredBatch = relayService.relayDueEvents();

        assertThat(recoveredBatch.publishedCount()).isOne();
        assertPublished("ievt_relay_recovery", NOW.plusMillis(100));
        assertThat(row("ievt_relay_recovery").retryCount()).isOne();
        assertThat(receiveLedgerMessage().getMessageProperties().getMessageId())
                .isEqualTo("ievt_relay_recovery");
        assertThat(recordingPublisher.transactionStates()).containsOnly(false);
    }

    private void save(OutboxEvent event) {
        new TransactionTemplate(transactionManager).executeWithoutResult(
                status -> outboxRepository.save(event)
        );
    }

    private static OutboxEvent pending(
            String eventId,
            String eventType,
            Instant availableAt
    ) {
        return OutboxEvent.pending(
                eventId,
                "PAYMENT_INTENT",
                "pi_" + eventId,
                eventType,
                """
                        {"merchantInternalId":41,"paymentPublicId":"pi_test",
                         "amountMinor":1000,"currency":"VND",
                         "occurredAt":"%s"}
                        """.formatted(OCCURRED_AT),
                OCCURRED_AT,
                availableAt
        );
    }

    private RelayRow row(String eventId) {
        return jdbcTemplate.queryForObject(
                """
                        SELECT status, available_at, published_at, retry_count, last_error
                        FROM outbox_events
                        WHERE event_id = ?
                        """,
                (resultSet, rowNumber) -> new RelayRow(
                        resultSet.getString("status"),
                        resultSet.getObject("available_at", OffsetDateTime.class).toInstant(),
                        nullableInstant(resultSet.getObject(
                                "published_at",
                                OffsetDateTime.class
                        )),
                        resultSet.getInt("retry_count"),
                        resultSet.getString("last_error")
                ),
                eventId
        );
    }

    private void assertPublished(String eventId, Instant publishedAt) {
        RelayRow row = row(eventId);
        assertThat(row.status()).isEqualTo("PUBLISHED");
        assertThat(row.publishedAt()).isEqualTo(publishedAt);
        assertThat(row.lastError()).isNull();
    }

    private void assertFailed(String eventId, int retryCount, Instant availableAt) {
        RelayRow row = row(eventId);
        assertThat(row.status()).isEqualTo("FAILED");
        assertThat(row.retryCount()).isEqualTo(retryCount);
        assertThat(row.availableAt()).isEqualTo(availableAt);
        assertThat(row.publishedAt()).isNull();
        assertThat(row.lastError()).isEqualTo(
                "RabbitMQ publication failed: "
                        + (eventId.equals("ievt_relay_recovery")
                        ? "TRANSPORT_FAILURE"
                        : "UNROUTABLE")
        );
    }

    private Message receiveLedgerMessage() {
        Message message = rabbitTemplate.receive(
                properties.topology().ledgerQueue(),
                5_000L
        );
        assertThat(message).isNotNull();
        return message;
    }

    private void stopRabbitApplication() throws Exception {
        org.testcontainers.containers.Container.ExecResult result =
                RABBITMQ.execInContainer(
                "rabbitmqctl",
                "stop_app"
        );
        assertThat(result.getExitCode()).isZero();
        connectionFactory.resetConnection();
    }

    private void ensureRabbitRunning() throws Exception {
        org.testcontainers.containers.Container.ExecResult result =
                RABBITMQ.execInContainer(
                "rabbitmqctl",
                "start_app"
        );
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

    private static Instant nullableInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private record RelayRow(
            String status,
            Instant availableAt,
            Instant publishedAt,
            int retryCount,
            String lastError
    ) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RelayTestConfiguration {

        @Bean
        @Primary
        MutableClock mutableRelayClock() {
            return new MutableClock(NOW);
        }

        @Bean
        @Primary
        RecordingPublisher recordingPublisher(
                ConfirmedRabbitIntegrationEventPublisher delegate
        ) {
            return new RecordingPublisher(delegate);
        }
    }

    static final class RecordingPublisher implements IntegrationEventTransportPublisher {

        private final ConfirmedRabbitIntegrationEventPublisher delegate;
        private final List<String> eventIds = new CopyOnWriteArrayList<>();
        private final List<Boolean> transactionStates = new CopyOnWriteArrayList<>();

        private RecordingPublisher(ConfirmedRabbitIntegrationEventPublisher delegate) {
            this.delegate = delegate;
        }

        @Override
        public IntegrationEventPublicationResult publish(
                IntegrationEventEnvelope envelope
        ) {
            eventIds.add(envelope.eventId());
            transactionStates.add(
                    TransactionSynchronizationManager.isActualTransactionActive()
            );
            if (envelope.eventId().equals("ievt_relay_unsafe")) {
                throw new IllegalStateException(
                        "amqp://admin:password@broker/vhost\nAuthorization: secret"
                );
            }
            return delegate.publish(envelope);
        }

        List<String> eventIds() {
            return List.copyOf(eventIds);
        }

        List<Boolean> transactionStates() {
            return List.copyOf(transactionStates);
        }

        void reset() {
            eventIds.clear();
            transactionStates.clear();
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
