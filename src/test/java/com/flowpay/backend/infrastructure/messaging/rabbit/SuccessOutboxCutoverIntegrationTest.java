package com.flowpay.backend.infrastructure.messaging.rabbit;

import com.flowpay.backend.common.money.Money;
import com.flowpay.backend.infrastructure.messaging.FlowPayMessagingProperties;
import com.flowpay.backend.infrastructure.messaging.outbox.relay.OutboxRelayBatchResult;
import com.flowpay.backend.infrastructure.messaging.outbox.relay.OutboxRelayService;
import com.flowpay.backend.payment.application.FinalizePaymentConfirmationCommand;
import com.flowpay.backend.payment.application.FinalizePaymentConfirmationService;
import com.flowpay.backend.payment.application.FinalizedPaymentConfirmation;
import com.flowpay.backend.payment.application.PaymentIntentRepository;
import com.flowpay.backend.payment.application.PaymentProviderResult;
import com.flowpay.backend.payment.application.PaymentTransactionRepository;
import com.flowpay.backend.payment.domain.PaymentIntent;
import com.flowpay.backend.payment.domain.PaymentStatus;
import com.flowpay.backend.payment.domain.PaymentTransaction;
import com.flowpay.backend.payment.domain.PaymentTransactionStatus;
import com.flowpay.backend.payment.domain.ProviderOutcome;
import com.flowpay.backend.refund.application.FinalizeRefundCommand;
import com.flowpay.backend.refund.application.FinalizeRefundService;
import com.flowpay.backend.refund.application.FinalizedRefund;
import com.flowpay.backend.refund.application.RefundProviderResult;
import com.flowpay.backend.refund.application.RefundRepository;
import com.flowpay.backend.refund.domain.Refund;
import com.flowpay.backend.refund.domain.RefundProviderOutcome;
import com.flowpay.backend.refund.domain.RefundReason;
import com.flowpay.backend.refund.domain.RefundStatus;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.rabbitmq.RabbitMQContainer;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@ActiveProfiles("test")
@Import(SuccessOutboxCutoverIntegrationTest.ClockConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SuccessOutboxCutoverIntegrationTest extends PostgresIntegrationTest {

    private static final Instant CREATED_AT = Instant.parse("2026-09-02T10:00:00Z");
    private static final Instant STARTED_AT = Instant.parse("2026-09-02T10:00:05Z");
    private static final Instant COMPLETED_AT = Instant.parse("2026-09-02T10:00:10Z");

    @Container
    private static final RabbitMQContainer RABBITMQ =
            new RabbitMQContainer("rabbitmq:4.3-management-alpine");

    @Autowired
    private FinalizePaymentConfirmationService finalizationService;

    @Autowired
    private PaymentIntentRepository paymentIntentRepository;

    @Autowired
    private PaymentTransactionRepository paymentTransactionRepository;

    @Autowired
    private FinalizeRefundService refundFinalizationService;

    @Autowired
    private RefundRepository refundRepository;

    @Autowired
    private OutboxRelayService relayService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    @Autowired
    private FlowPayMessagingProperties messagingProperties;

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
        jdbcTemplate.update("""
                TRUNCATE TABLE outbox_events, ledger_entries, ledger_transactions,
                    ledger_accounts, refunds, payment_transactions, payment_intents,
                    merchant_members, merchant_api_keys, refresh_tokens, merchants, users
                RESTART IDENTITY CASCADE
                """);
        rabbitAdmin.purgeQueue(messagingProperties.topology().ledgerQueue(), true);
        rabbitAdmin.purgeQueue(
                messagingProperties.topology().ledgerDeadLetterQueue(),
                true
        );
    }

    @AfterEach
    void cleanQueues() {
        rabbitAdmin.purgeQueue(messagingProperties.topology().ledgerQueue(), true);
        rabbitAdmin.purgeQueue(
                messagingProperties.topology().ledgerDeadLetterQueue(),
                true
        );
    }

    @Test
    void shouldCommitSourceAndOutboxBeforeEventualLedgerPosting() {
        PreparedPayment prepared = insertProcessingPayment();

        FinalizedPaymentConfirmation result = finalizationService.finalizeConfirmation(
                new FinalizePaymentConfirmationCommand(
                        prepared.payment().publicId(),
                        prepared.transaction().publicId(),
                        new PaymentProviderResult(
                                "SIMULATOR",
                                ProviderOutcome.SUCCESS,
                                "sim_cutover_success",
                                null,
                                null
                        )
                )
        );

        PaymentIntent committedPayment = paymentIntentRepository
                .findByPublicId(prepared.payment().publicId())
                .orElseThrow();
        PaymentTransaction committedTransaction = paymentTransactionRepository
                .findByPublicId(prepared.transaction().publicId())
                .orElseThrow();
        assertThat(result.paymentStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(committedPayment.status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(committedTransaction.status())
                .isEqualTo(PaymentTransactionStatus.SUCCEEDED);
        assertThat(countRows("outbox_events")).isEqualTo(1L);
        assertThat(outboxStatus("payment.succeeded.v1", prepared.payment().publicId()))
                .isEqualTo("PENDING");
        assertThat(countRows("ledger_transactions")).isZero();
        assertThat(countRows("ledger_entries")).isZero();

        OutboxRelayBatchResult relayResult = relayService.relayDueEvents();

        assertThat(relayResult).isEqualTo(new OutboxRelayBatchResult(1, 1, 0));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(outboxStatus("payment.succeeded.v1", prepared.payment().publicId()))
                    .isEqualTo("PUBLISHED");
            assertThat(countRows("ledger_transactions")).isEqualTo(1L);
            assertThat(countRows("ledger_entries")).isEqualTo(2L);
        });
        assertThat(jdbcTemplate.queryForObject("""
                SELECT posting_type || ':' || reference_type || ':' || reference_id
                    || ':' || TRIM(currency)
                FROM ledger_transactions
                """, String.class)).isEqualTo(
                "PAYMENT_SUCCEEDED:PAYMENT_INTENT:pi_cutover_success:VND"
        );
        assertThat(jdbcTemplate.queryForObject("""
                SELECT occurred_at
                FROM ledger_transactions
                WHERE reference_id = ?
                """, OffsetDateTime.class, prepared.payment().publicId()).toInstant())
                .isEqualTo(committedTransaction.completedAt());
        assertThat(jdbcTemplate.queryForList("""
                SELECT e.entry_no || ':' || e.direction || ':' || e.amount_minor
                    || ':' || a.account_type || ':'
                    || COALESCE(a.owner_id::text, '<null>') || ':' || TRIM(a.currency)
                FROM ledger_entries e
                JOIN ledger_accounts a ON a.id = e.ledger_account_id
                ORDER BY e.entry_no
                """, String.class)).containsExactly(
                "1:DEBIT:750000:SYSTEM_CLEARING:<null>:VND",
                "2:CREDIT:750000:MERCHANT_PAYABLE:"
                        + prepared.payment().merchantId()
                        + ":VND"
        );
    }

    @Test
    void shouldCommitRefundAndOutboxBeforeEventualLedgerPosting() {
        PreparedRefund prepared = insertProcessingRefund();

        FinalizedRefund result = refundFinalizationService.finalizeRefund(
                new FinalizeRefundCommand(
                        prepared.merchantId(),
                        prepared.refundPublicId(),
                        prepared.paymentPublicId(),
                        new RefundProviderResult(
                                "SIMULATOR",
                                RefundProviderOutcome.SUCCESS,
                                "sim_refund_cutover_success",
                                null,
                                null
                        )
                )
        );

        Refund committedRefund = refundRepository
                .findByPublicIdAndMerchantId(
                        prepared.refundPublicId(),
                        prepared.merchantId()
                )
                .orElseThrow();
        assertThat(result.status()).isEqualTo(RefundStatus.SUCCEEDED);
        assertThat(committedRefund.status()).isEqualTo(RefundStatus.SUCCEEDED);
        assertThat(paymentCapacity(prepared.paymentPublicId()))
                .isEqualTo("PARTIALLY_REFUNDED:300000:0");
        assertThat(countRows("outbox_events")).isEqualTo(1L);
        assertThat(outboxStatus("refund.succeeded.v1", prepared.refundPublicId()))
                .isEqualTo("PENDING");
        assertThat(countRows("ledger_transactions")).isZero();
        assertThat(countRows("ledger_entries")).isZero();

        OutboxRelayBatchResult relayResult = relayService.relayDueEvents();

        assertThat(relayResult).isEqualTo(new OutboxRelayBatchResult(1, 1, 0));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(outboxStatus("refund.succeeded.v1", prepared.refundPublicId()))
                    .isEqualTo("PUBLISHED");
            assertThat(countRows("ledger_transactions")).isEqualTo(1L);
            assertThat(countRows("ledger_entries")).isEqualTo(2L);
        });
        assertThat(jdbcTemplate.queryForObject("""
                SELECT posting_type || ':' || reference_type || ':' || reference_id
                    || ':' || TRIM(currency)
                FROM ledger_transactions
                """, String.class)).isEqualTo(
                "REFUND_SUCCEEDED:REFUND:re_cutover_success:VND"
        );
        assertThat(jdbcTemplate.queryForObject("""
                SELECT occurred_at
                FROM ledger_transactions
                WHERE reference_id = ?
                """, OffsetDateTime.class, prepared.refundPublicId()).toInstant())
                .isEqualTo(committedRefund.completedAt());
        assertThat(jdbcTemplate.queryForList("""
                SELECT e.entry_no || ':' || e.direction || ':' || e.amount_minor
                    || ':' || a.account_type || ':'
                    || COALESCE(a.owner_id::text, '<null>') || ':' || TRIM(a.currency)
                FROM ledger_entries e
                JOIN ledger_accounts a ON a.id = e.ledger_account_id
                ORDER BY e.entry_no
                """, String.class)).containsExactly(
                "1:DEBIT:300000:MERCHANT_PAYABLE:"
                        + prepared.merchantId()
                        + ":VND",
                "2:CREDIT:300000:SYSTEM_CLEARING:<null>:VND"
        );
    }

    private PreparedPayment insertProcessingPayment() {
        long merchantId = insertMerchant();
        PaymentIntent payment = paymentIntentRepository.save(PaymentIntent.create(
                "pi_cutover_success",
                merchantId,
                "ORDER-CUTOVER",
                "Payment Outbox cutover",
                Money.of(750_000L, "VND"),
                CREATED_AT
        ));
        payment.startProcessing(STARTED_AT);
        PaymentIntent processingPayment = paymentIntentRepository.save(payment);
        PaymentTransaction transaction = paymentTransactionRepository.save(
                PaymentTransaction.createProcessing(
                        "ptxn_cutover_success",
                        processingPayment.internalId(),
                        1,
                        "SIMULATOR",
                        STARTED_AT
                )
        );
        return new PreparedPayment(processingPayment, transaction);
    }

    private long insertMerchant() {
        Long merchantId = jdbcTemplate.queryForObject("""
                INSERT INTO merchants (
                    public_id, name, status, created_at, updated_at, version
                )
                VALUES ('mrc_cutover_success', 'Payment Cutover Store', 'ACTIVE', ?, ?, 0)
                RETURNING id
                """,
                Long.class,
                CREATED_AT.atOffset(ZoneOffset.UTC),
                CREATED_AT.atOffset(ZoneOffset.UTC)
        );
        return java.util.Objects.requireNonNull(merchantId);
    }

    private PreparedRefund insertProcessingRefund() {
        long merchantId = insertMerchant("mrc_refund_cutover_success");
        Long paymentInternalId = jdbcTemplate.queryForObject("""
                INSERT INTO payment_intents (
                    public_id, merchant_id, merchant_order_id, description,
                    amount_minor, currency, status, refunded_amount_minor,
                    refund_reserved_minor, created_at, updated_at, version
                )
                VALUES ('pi_refund_cutover_success', ?, 'ORDER-REFUND-CUTOVER',
                    'Refund Outbox cutover', 1000000, 'VND', 'SUCCEEDED',
                    0, 300000, ?, ?, 0)
                RETURNING id
                """,
                Long.class,
                merchantId,
                CREATED_AT.atOffset(ZoneOffset.UTC),
                STARTED_AT.atOffset(ZoneOffset.UTC)
        );
        Refund refund = Refund.create(
                "re_cutover_success",
                merchantId,
                java.util.Objects.requireNonNull(paymentInternalId),
                Money.of(300_000L, "VND"),
                RefundReason.of("Customer request"),
                "SIMULATOR",
                CREATED_AT
        );
        refund.startProcessing(STARTED_AT);
        Refund processingRefund = refundRepository.save(refund);
        return new PreparedRefund(
                merchantId,
                "pi_refund_cutover_success",
                processingRefund.publicId()
        );
    }

    private long insertMerchant(String publicId) {
        Long merchantId = jdbcTemplate.queryForObject("""
                INSERT INTO merchants (
                    public_id, name, status, created_at, updated_at, version
                )
                VALUES (?, 'Refund Cutover Store', 'ACTIVE', ?, ?, 0)
                RETURNING id
                """,
                Long.class,
                publicId,
                CREATED_AT.atOffset(ZoneOffset.UTC),
                CREATED_AT.atOffset(ZoneOffset.UTC)
        );
        return java.util.Objects.requireNonNull(merchantId);
    }

    private String outboxStatus(String eventType, String aggregateId) {
        return jdbcTemplate.queryForObject("""
                SELECT status
                FROM outbox_events
                WHERE event_type = ?
                  AND aggregate_id = ?
                """, String.class, eventType, aggregateId);
    }

    private String paymentCapacity(String paymentPublicId) {
        return jdbcTemplate.queryForObject("""
                SELECT status || ':' || refunded_amount_minor || ':' || refund_reserved_minor
                FROM payment_intents
                WHERE public_id = ?
                """, String.class, paymentPublicId);
    }

    private long countRows(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private record PreparedPayment(
            PaymentIntent payment,
            PaymentTransaction transaction
    ) {
    }

    private record PreparedRefund(
            long merchantId,
            String paymentPublicId,
            String refundPublicId
    ) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfiguration {

        @Bean
        @Primary
        Clock cutoverClock() {
            return Clock.fixed(COMPLETED_AT, ZoneOffset.UTC);
        }
    }
}
