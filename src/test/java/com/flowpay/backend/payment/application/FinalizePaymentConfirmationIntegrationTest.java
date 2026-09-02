package com.flowpay.backend.payment.application;

import com.flowpay.backend.common.money.Money;
import com.flowpay.backend.payment.domain.PaymentIntent;
import com.flowpay.backend.payment.domain.PaymentStatus;
import com.flowpay.backend.payment.domain.PaymentTransaction;
import com.flowpay.backend.payment.domain.PaymentTransactionStatus;
import com.flowpay.backend.payment.domain.ProviderOutcome;
import com.flowpay.backend.testing.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class FinalizePaymentConfirmationIntegrationTest extends PostgresIntegrationTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-29T08:00:00Z");
    private static final Instant STARTED_AT = Instant.parse("2026-08-29T08:00:05Z");

    @Autowired
    private FinalizePaymentConfirmationService service;

    @Autowired
    private PaymentIntentRepository paymentIntentRepository;

    @Autowired
    private PaymentTransactionRepository paymentTransactionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanData() {
        dropOutboxFailureTrigger();
        jdbcTemplate.update(
                "TRUNCATE TABLE outbox_events, ledger_entries, ledger_transactions, "
                        + "ledger_accounts, "
                        + "payment_transactions, payment_intents, merchant_members, "
                        + "merchant_api_keys, refresh_tokens, merchants, users "
                        + "RESTART IDENTITY CASCADE"
        );
    }

    @ParameterizedTest
    @MethodSource("providerOutcomes")
    void shouldCommitPaymentAndTransactionForEveryNormalizedOutcome(
            PaymentProviderResult providerResult,
            PaymentStatus expectedPaymentStatus,
            PaymentTransactionStatus expectedTransactionStatus
    ) {
        PreparedState prepared = insertProcessingConfirmation(providerResult.outcome().name());

        FinalizedPaymentConfirmation result = service.finalizeConfirmation(
                new FinalizePaymentConfirmationCommand(
                        prepared.payment().publicId(),
                        prepared.transaction().publicId(),
                        providerResult
                )
        );

        PaymentIntent payment = paymentIntentRepository
                .findByPublicId(prepared.payment().publicId())
                .orElseThrow();
        PaymentTransaction transaction = paymentTransactionRepository
                .findByPublicId(prepared.transaction().publicId())
                .orElseThrow();

        assertThat(payment.status()).isEqualTo(expectedPaymentStatus);
        assertThat(transaction.status()).isEqualTo(expectedTransactionStatus);
        assertThat(transaction.providerTransactionId())
                .isEqualTo(providerResult.providerTransactionId());
        assertThat(transaction.failureCode()).isEqualTo(providerResult.failureCode());
        assertThat(transaction.failureMessage()).isEqualTo(providerResult.failureMessage());
        assertThat(transaction.completedAt()).isAfterOrEqualTo(STARTED_AT);
        assertThat(transaction.version()).isEqualTo(1L);
        if (providerResult.outcome() == ProviderOutcome.UNKNOWN) {
            assertThat(payment.updatedAt()).isEqualTo(STARTED_AT);
            assertThat(payment.version()).isEqualTo(1L);
        } else {
            assertThat(payment.updatedAt()).isEqualTo(transaction.completedAt());
            assertThat(payment.version()).isEqualTo(2L);
        }
        assertThat(result).isEqualTo(new FinalizedPaymentConfirmation(
                payment.publicId(),
                expectedPaymentStatus,
                transaction.publicId(),
                expectedTransactionStatus,
                transaction.provider(),
                transaction.providerTransactionId(),
                transaction.failureCode(),
                transaction.failureMessage()
        ));
        if (providerResult.outcome() == ProviderOutcome.SUCCESS) {
            assertPaymentSuccessOutbox(payment, transaction.completedAt());
        } else {
            assertThat(countRows("outbox_events")).isZero();
            assertThat(countRows("ledger_transactions")).isZero();
            assertThat(countRows("ledger_entries")).isZero();
            assertThat(countRows("ledger_accounts")).isZero();
        }
    }

    @Test
    void shouldRollbackPaymentAndTransactionWhenOutboxPersistenceFails() {
        PreparedState prepared = insertProcessingConfirmation("outbox_rollback");
        installOutboxFailureTrigger();

        try {
            assertThatThrownBy(() -> service.finalizeConfirmation(
                    new FinalizePaymentConfirmationCommand(
                            prepared.payment().publicId(),
                            prepared.transaction().publicId(),
                            result(ProviderOutcome.SUCCESS, "sim_rollback", null, null)
                    )
            )).isInstanceOf(RuntimeException.class)
                    .hasRootCauseInstanceOf(java.sql.SQLException.class);
        } finally {
            dropOutboxFailureTrigger();
        }

        PaymentIntent payment = paymentIntentRepository
                .findByPublicId(prepared.payment().publicId())
                .orElseThrow();
        PaymentTransaction transaction = paymentTransactionRepository
                .findByPublicId(prepared.transaction().publicId())
                .orElseThrow();
        assertThat(payment.status()).isEqualTo(PaymentStatus.PROCESSING);
        assertThat(payment.updatedAt()).isEqualTo(STARTED_AT);
        assertThat(transaction.status()).isEqualTo(PaymentTransactionStatus.PROCESSING);
        assertThat(transaction.completedAt()).isNull();
        assertThat(countRows("outbox_events")).isZero();
        assertThat(countRows("ledger_transactions")).isZero();
        assertThat(countRows("ledger_entries")).isZero();
        assertThat(countRows("ledger_accounts")).isZero();
    }

    private PreparedState insertProcessingConfirmation(String suffix) {
        long merchantId = insertActiveMerchant("mrc_finalize_" + suffix.toLowerCase());
        PaymentIntent payment = paymentIntentRepository.save(PaymentIntent.create(
                "pi_finalize_" + suffix.toLowerCase(),
                merchantId,
                "ORDER-FINALIZE-" + suffix,
                "Finalize confirmation",
                Money.of(50_000L, "VND"),
                CREATED_AT
        ));
        payment.startProcessing(STARTED_AT);
        PaymentIntent processingPayment = paymentIntentRepository.save(payment);
        PaymentTransaction transaction = paymentTransactionRepository.save(
                PaymentTransaction.createProcessing(
                        "ptxn_finalize_" + suffix.toLowerCase(),
                        processingPayment.internalId(),
                        1,
                        "SIMULATOR",
                        STARTED_AT
                )
        );
        return new PreparedState(processingPayment, transaction);
    }

    private long insertActiveMerchant(String publicId) {
        Long id = jdbcTemplate.queryForObject(
                """
                INSERT INTO merchants (public_id, name, status, created_at, updated_at, version)
                VALUES (?, ?, 'ACTIVE', ?, ?, 0)
                RETURNING id
                """,
                Long.class,
                publicId,
                "Finalize Confirmation Store",
                CREATED_AT.atOffset(ZoneOffset.UTC),
                CREATED_AT.atOffset(ZoneOffset.UTC)
        );
        return id;
    }

    private void assertPaymentSuccessOutbox(PaymentIntent payment, Instant completedAt) {
        assertThat(countRows("outbox_events")).isEqualTo(1L);
        Map<String, Object> event = jdbcTemplate.queryForMap("""
                SELECT event_id, aggregate_type, aggregate_id, event_type, status,
                    payload ->> 'merchantInternalId' AS merchant_internal_id,
                    payload ->> 'paymentPublicId' AS payment_public_id,
                    payload ->> 'amountMinor' AS amount_minor,
                    payload ->> 'currency' AS currency,
                    payload ->> 'occurredAt' AS payload_occurred_at
                FROM outbox_events
                """);
        assertThat(event.get("event_id")).asString().startsWith("ievt_");
        assertThat(event.get("aggregate_type")).isEqualTo("PAYMENT_INTENT");
        assertThat(event.get("aggregate_id")).isEqualTo(payment.publicId());
        assertThat(event.get("event_type")).isEqualTo("payment.succeeded.v1");
        assertThat(event.get("status")).isEqualTo("PENDING");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT occurred_at FROM outbox_events",
                OffsetDateTime.class
        ).toInstant()).isEqualTo(completedAt);
        assertThat(event.get("merchant_internal_id")).isEqualTo(
                Long.toString(payment.merchantId())
        );
        assertThat(event.get("payment_public_id")).isEqualTo(payment.publicId());
        assertThat(event.get("amount_minor")).isEqualTo("50000");
        assertThat(event.get("currency")).isEqualTo("VND");
        assertThat(Instant.parse((String) event.get("payload_occurred_at")))
                .isEqualTo(completedAt);
        assertThat(jdbcTemplate.queryForList("""
                SELECT jsonb_object_keys(payload)
                FROM outbox_events
                ORDER BY jsonb_object_keys(payload)
                """, String.class)).containsExactly(
                "amountMinor",
                "currency",
                "merchantInternalId",
                "occurredAt",
                "paymentPublicId"
        );
        assertThat(countRows("ledger_transactions")).isZero();
        assertThat(countRows("ledger_entries")).isZero();
        assertThat(countRows("ledger_accounts")).isZero();
    }

    private void installOutboxFailureTrigger() {
        jdbcTemplate.execute("""
                CREATE OR REPLACE FUNCTION fail_payment_outbox_insert()
                RETURNS trigger AS $$
                BEGIN
                    IF NEW.event_type = 'payment.succeeded.v1' THEN
                        RAISE EXCEPTION 'simulated Outbox persistence failure';
                    END IF;
                    RETURN NEW;
                END;
                $$ LANGUAGE plpgsql
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER fail_payment_outbox_insert_trigger
                BEFORE INSERT ON outbox_events
                FOR EACH ROW EXECUTE FUNCTION fail_payment_outbox_insert()
                """);
    }

    private void dropOutboxFailureTrigger() {
        jdbcTemplate.execute("""
                DROP TRIGGER IF EXISTS fail_payment_outbox_insert_trigger
                ON outbox_events
                """);
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS fail_payment_outbox_insert()");
    }

    private long countRows(String table) {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return count == null ? 0L : count;
    }

    private static Stream<Arguments> providerOutcomes() {
        return Stream.of(
                Arguments.of(
                        result(ProviderOutcome.SUCCESS, "sim_success", null, null),
                        PaymentStatus.SUCCEEDED,
                        PaymentTransactionStatus.SUCCEEDED
                ),
                Arguments.of(
                        result(
                                ProviderOutcome.DECLINED,
                                "sim_declined",
                                "CARD_DECLINED",
                                "The provider declined the payment."
                        ),
                        PaymentStatus.FAILED,
                        PaymentTransactionStatus.FAILED
                ),
                Arguments.of(
                        result(
                                ProviderOutcome.UNKNOWN,
                                null,
                                "PROVIDER_TIMEOUT",
                                "The provider outcome is unknown."
                        ),
                        PaymentStatus.PROCESSING,
                        PaymentTransactionStatus.UNKNOWN
                ),
                Arguments.of(
                        result(
                                ProviderOutcome.TECHNICAL_FAILURE,
                                null,
                                "PROVIDER_UNAVAILABLE",
                                "The provider operation did not complete."
                        ),
                        PaymentStatus.FAILED,
                        PaymentTransactionStatus.FAILED
                )
        );
    }

    private static PaymentProviderResult result(
            ProviderOutcome outcome,
            String providerTransactionId,
            String failureCode,
            String failureMessage
    ) {
        return new PaymentProviderResult(
                "SIMULATOR",
                outcome,
                providerTransactionId,
                failureCode,
                failureMessage
        );
    }

    private record PreparedState(
            PaymentIntent payment,
            PaymentTransaction transaction
    ) {
    }
}
