package com.flowpay.backend.infrastructure.messaging.outbox;

import com.flowpay.backend.payment.application.event.PaymentIntegrationEventPublisher;
import com.flowpay.backend.payment.application.event.PaymentSucceededEventV1;
import com.flowpay.backend.payment.application.event.PaymentProcessingEventV1;
import com.flowpay.backend.payment.application.event.PaymentFailedEventV1;
import com.flowpay.backend.refund.application.event.RefundIntegrationEventPublisher;
import com.flowpay.backend.refund.application.event.RefundSucceededEventV1;
import com.flowpay.backend.refund.application.event.RefundProcessingEventV1;
import com.flowpay.backend.refund.application.event.RefundFailedEventV1;
import com.flowpay.backend.testing.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(TransactionalOutboxEventPublisherIntegrationTest.FixedClockConfiguration.class)
class TransactionalOutboxEventPublisherIntegrationTest extends PostgresIntegrationTest {

    private static final Instant PAYMENT_OCCURRED_AT = Instant.parse(
            "2026-09-01T11:00:00Z"
    );
    private static final Instant REFUND_OCCURRED_AT = Instant.parse(
            "2026-09-01T11:05:00Z"
    );
    private static final Instant CREATED_AT = Instant.parse("2026-09-01T11:10:00Z");
    private static final String SOURCE_MARKER = "mrc_outbox_writer_rollback";

    @Autowired
    private PaymentIntegrationEventPublisher paymentPublisher;

    @Autowired
    private RefundIntegrationEventPublisher refundPublisher;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private IntegrationEventEnvelopeMapper envelopeMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void cleanOutboxData() {
        cleanData();
    }

    @AfterEach
    void cleanOutboxDataAfterTest() {
        cleanData();
    }

    @Test
    void shouldPersistExactPaymentPayloadAndStableEnvelopeMetadata() throws Exception {
        inTransaction(() -> paymentPublisher.publish(paymentEvent("pi_outbox_payment")));

        OutboxEvent event = singleDueEvent(CREATED_AT);
        assertThat(event.eventId()).startsWith("ievt_");
        assertThat(event.aggregateType()).isEqualTo("PAYMENT_INTENT");
        assertThat(event.aggregateId()).isEqualTo("pi_outbox_payment");
        assertThat(event.eventType()).isEqualTo("payment.succeeded.v1");
        assertThat(event.status()).isEqualTo(OutboxStatus.PENDING);
        assertThat(event.occurredAt()).isEqualTo(PAYMENT_OCCURRED_AT);
        assertThat(event.availableAt()).isEqualTo(CREATED_AT);
        assertThat(event.createdAt()).isEqualTo(CREATED_AT);
        assertThat(event.publishedAt()).isNull();
        assertThat(event.retryCount()).isZero();
        assertThat(event.lastError()).isNull();

        JsonNode payload = objectMapper.readTree(event.payload());
        assertThat(payload).isEqualTo(objectMapper.readTree("""
                {
                  "merchantInternalId": 15,
                  "paymentPublicId": "pi_outbox_payment",
                  "amountMinor": 1000000,
                  "currency": "VND",
                  "occurredAt": "2026-09-01T11:00:00Z"
                }
                """));
        assertSafePayload(
                payload,
                event.payload(),
                Set.of(
                        "merchantInternalId",
                        "paymentPublicId",
                        "amountMinor",
                        "currency",
                        "occurredAt"
                )
        );

        IntegrationEventEnvelope envelope = envelopeMapper.from(event);
        assertThat(envelope.eventId()).isEqualTo(event.eventId());
        assertThat(envelope.eventType()).isEqualTo(event.eventType());
        assertThat(envelope.aggregateType()).isEqualTo(event.aggregateType());
        assertThat(envelope.aggregateId()).isEqualTo(event.aggregateId());
        assertThat(envelope.occurredAt()).isEqualTo(PAYMENT_OCCURRED_AT);
        assertThat(envelope.payload()).isEqualTo(payload);
    }

    @Test
    void shouldPersistRefundPayloadWithUniqueInternalEventIdentity() throws Exception {
        inTransaction(() -> {
            paymentPublisher.publish(paymentEvent("pi_outbox_correlation"));
            refundPublisher.publish(new RefundSucceededEventV1(
                    15L,
                    "re_outbox_refund",
                    "pi_outbox_correlation",
                    300_000L,
                    "VND",
                    REFUND_OCCURRED_AT
            ));
        });

        List<OutboxEvent> events = outboxRepository.findDueUnpublished(CREATED_AT, 10);
        assertThat(events).hasSize(2);
        assertThat(events).extracting(OutboxEvent::eventId)
                .allMatch(id -> id.startsWith("ievt_"))
                .doesNotHaveDuplicates();

        OutboxEvent refund = events.stream()
                .filter(event -> event.eventType().equals("refund.succeeded.v1"))
                .findFirst()
                .orElseThrow();
        assertThat(refund.aggregateType()).isEqualTo("REFUND");
        assertThat(refund.aggregateId()).isEqualTo("re_outbox_refund");
        JsonNode payload = objectMapper.readTree(refund.payload());
        assertThat(payload).isEqualTo(objectMapper.readTree("""
                {
                  "merchantInternalId": 15,
                  "refundPublicId": "re_outbox_refund",
                  "paymentPublicId": "pi_outbox_correlation",
                  "amountMinor": 300000,
                  "currency": "VND",
                  "occurredAt": "2026-09-01T11:05:00Z"
                }
                """));
        assertSafePayload(
                payload,
                refund.payload(),
                Set.of(
                        "merchantInternalId",
                        "refundPublicId",
                        "paymentPublicId",
                        "amountMinor",
                        "currency",
                        "occurredAt"
                )
        );
    }

    @Test
    void shouldRequireAnExistingCallerTransaction() {
        assertThatThrownBy(() -> paymentPublisher.publish(paymentEvent("pi_no_tx")))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(countRows("outbox_events")).isZero();
    }

    @Test
    void shouldRollBackSourceMutationAndOutboxInsertTogether() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            jdbcTemplate.update("""
                    INSERT INTO merchants (public_id, name, status, created_at, updated_at)
                    VALUES (?, 'Outbox rollback marker', 'ACTIVE', ?, ?)
                    """,
                    SOURCE_MARKER,
                    CREATED_AT.atOffset(ZoneOffset.UTC),
                    CREATED_AT.atOffset(ZoneOffset.UTC));
            paymentPublisher.publish(paymentEvent("pi_rollback"));
            throw new ForcedSourceRollbackException();
        })).isInstanceOf(ForcedSourceRollbackException.class);

        assertThat(countRows("outbox_events")).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM merchants WHERE public_id = ?",
                Long.class,
                SOURCE_MARKER
        )).isZero();
    }

    @Test
    void shouldApplyConditionalRelayStateChangesAndSelectOnlyDueUnpublishedEvents() {
        Instant base = CREATED_AT.minusSeconds(100);
        inTransaction(() -> {
            outboxRepository.save(pending("ievt_due_pending", "pi_due", base));
            outboxRepository.save(pending(
                    "ievt_due_failed",
                    "pi_failed",
                    base.plusSeconds(1)
            ));
            outboxRepository.save(pending(
                    "ievt_published",
                    "pi_published",
                    base.plusSeconds(2)
            ));
            outboxRepository.save(pending(
                    "ievt_future",
                    "pi_future",
                    CREATED_AT.plusSeconds(1)
            ));
        });

        assertThat(outboxRepository.recordFailure(
                "ievt_due_failed",
                base.plusSeconds(3),
                " Broker unavailable "
        )).isTrue();
        assertThat(outboxRepository.markPublished(
                "ievt_published",
                base.plusSeconds(4)
        )).isTrue();

        List<OutboxEvent> due = outboxRepository.findDueUnpublished(CREATED_AT, 10);
        assertThat(due).extracting(OutboxEvent::eventId)
                .containsExactly("ievt_due_pending", "ievt_due_failed");
        OutboxEvent failed = due.get(1);
        assertThat(failed.status()).isEqualTo(OutboxStatus.FAILED);
        assertThat(failed.retryCount()).isOne();
        assertThat(failed.lastError()).isEqualTo("Broker unavailable");
        assertThat(failed.availableAt()).isEqualTo(base.plusSeconds(3));

        assertThat(outboxRepository.recordFailure(
                "ievt_published",
                CREATED_AT.plusSeconds(20),
                "late negative result"
        )).isFalse();
        assertThat(rowStatus("ievt_published")).isEqualTo("PUBLISHED");
        assertThat(rowRetryCount("ievt_published")).isZero();
        assertThat(rowLastError("ievt_published")).isNull();
        assertThat(outboxRepository.markPublished(
                "ievt_due_failed",
                CREATED_AT.plusSeconds(5)
        )).isTrue();
        assertThat(outboxRepository.findDueUnpublished(CREATED_AT, 10))
                .extracting(OutboxEvent::eventId)
                .containsExactly("ievt_due_pending");
    }

    @Test
    void shouldRejectEnvelopePayloadOccurrenceMismatch() {
        OutboxEvent malformed = OutboxEvent.pending(
                "ievt_mismatch",
                "PAYMENT_INTENT",
                "pi_mismatch",
                "payment.succeeded.v1",
                """
                        {"merchantInternalId":15,"paymentPublicId":"pi_mismatch",
                         "amountMinor":1,"currency":"VND",
                         "occurredAt":"2026-09-01T10:59:59Z"}
                        """,
                PAYMENT_OCCURRED_AT,
                CREATED_AT
        );

        assertThatThrownBy(() -> envelopeMapper.from(malformed))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Outbox envelope and payload occurredAt must match");
    }

    @ParameterizedTest
    @ValueSource(strings = {"payment.processing.v1", "payment.failed.v1", "refund.processing.v1", "refund.failed.v1"})
    void newSourceTypesRequireCallerTransactionAndRollBackWithIt(String type) {
        Runnable publish = switch (type) {
            case "payment.processing.v1" -> () -> paymentPublisher.publish(
                    new PaymentProcessingEventV1(15, "pi_catalog", 1000, "VND", PAYMENT_OCCURRED_AT));
            case "payment.failed.v1" -> () -> paymentPublisher.publish(
                    new PaymentFailedEventV1(15, "pi_catalog", 1000, "VND", "PROVIDER_UNAVAILABLE",
                            "Operation failed.", PAYMENT_OCCURRED_AT));
            case "refund.processing.v1" -> () -> refundPublisher.publish(
                    new RefundProcessingEventV1(15, "re_catalog", "pi_catalog", 400, "VND", PAYMENT_OCCURRED_AT));
            case "refund.failed.v1" -> () -> refundPublisher.publish(
                    new RefundFailedEventV1(15, "re_catalog", "pi_catalog", 400, "VND",
                            "PROVIDER_UNAVAILABLE", "Operation failed.", PAYMENT_OCCURRED_AT));
            default -> throw new IllegalArgumentException("Unexpected test event");
        };
        assertThatThrownBy(publish::run).isInstanceOf(IllegalTransactionStateException.class);
        inTransaction(publish);
        OutboxEvent saved = singleDueEvent(CREATED_AT);
        assertThat(saved.eventType()).isEqualTo(type);
        assertThat(envelopeMapper.from(saved).occurredAt()).isEqualTo(PAYMENT_OCCURRED_AT);
        assertThatThrownBy(() -> inTransaction(() -> {
            publish.run();
            throw new ForcedSourceRollbackException();
        })).isInstanceOf(ForcedSourceRollbackException.class);
        assertThat(countRows("outbox_events")).isOne();
    }

    private PaymentSucceededEventV1 paymentEvent(String paymentPublicId) {
        return new PaymentSucceededEventV1(
                15L,
                paymentPublicId,
                1_000_000L,
                "VND",
                PAYMENT_OCCURRED_AT
        );
    }

    private static void assertSafePayload(
            JsonNode payload,
            String serializedPayload,
            Set<String> allowedFields
    ) {
        assertThat(payload.propertyNames()).containsExactlyInAnyOrderElementsOf(
                allowedFields
        );
        assertThat(serializedPayload)
                .doesNotContainIgnoringCase(
                        "authorization",
                        "idempotency-key",
                        "idempotencyKey",
                        "apiKey",
                        "password",
                        "credential",
                        "secret",
                        "requestHash",
                        "keyHash",
                        "ciphertext",
                        "version",
                        "providerResponse",
                        "rawResponse",
                        "PaymentIntentEntity",
                        "PaymentTransactionEntity",
                        "RefundEntity",
                        "@class",
                        "__TypeId__"
                );
    }

    private static OutboxEvent pending(
            String eventId,
            String aggregateId,
            Instant createdAt
    ) {
        return OutboxEvent.pending(
                eventId,
                "PAYMENT_INTENT",
                aggregateId,
                "payment.succeeded.v1",
                """
                        {"merchantInternalId":15,"paymentPublicId":"%s",
                         "amountMinor":1,"currency":"VND","occurredAt":"%s"}
                        """.formatted(aggregateId, PAYMENT_OCCURRED_AT),
                PAYMENT_OCCURRED_AT,
                createdAt
        );
    }

    private OutboxEvent singleDueEvent(Instant dueAt) {
        List<OutboxEvent> events = outboxRepository.findDueUnpublished(dueAt, 10);
        assertThat(events).hasSize(1);
        return events.getFirst();
    }

    private void inTransaction(Runnable operation) {
        new TransactionTemplate(transactionManager).executeWithoutResult(
                status -> operation.run()
        );
    }

    private long countRows(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private String rowStatus(String eventId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM outbox_events WHERE event_id = ?",
                String.class,
                eventId
        );
    }

    private int rowRetryCount(String eventId) {
        return jdbcTemplate.queryForObject(
                "SELECT retry_count FROM outbox_events WHERE event_id = ?",
                Integer.class,
                eventId
        );
    }

    private String rowLastError(String eventId) {
        return jdbcTemplate.queryForObject(
                "SELECT last_error FROM outbox_events WHERE event_id = ?",
                String.class,
                eventId
        );
    }

    private void cleanData() {
        jdbcTemplate.update("TRUNCATE TABLE outbox_events RESTART IDENTITY");
        jdbcTemplate.update(
                "DELETE FROM merchants WHERE public_id = ?",
                SOURCE_MARKER
        );
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfiguration {

        @Bean
        @Primary
        Clock fixedOutboxClock() {
            return Clock.fixed(CREATED_AT, ZoneOffset.UTC);
        }
    }

    private static final class ForcedSourceRollbackException extends RuntimeException {
    }
}
