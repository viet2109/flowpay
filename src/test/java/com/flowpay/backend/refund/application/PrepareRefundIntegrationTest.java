package com.flowpay.backend.refund.application;

import com.flowpay.backend.common.error.ApiException;
import com.flowpay.backend.common.error.ErrorCode;
import com.flowpay.backend.common.money.Money;
import com.flowpay.backend.common.security.MerchantApiPrincipal;
import com.flowpay.backend.idempotency.application.IdempotencyAcquisitionDecision;
import com.flowpay.backend.idempotency.application.IdempotencyCompletionCommand;
import com.flowpay.backend.idempotency.application.IdempotencyCompletionService;
import com.flowpay.backend.idempotency.domain.IdempotencyKey;
import com.flowpay.backend.idempotency.domain.IdempotencyOperation;
import com.flowpay.backend.idempotency.domain.IdempotencyRecord;
import com.flowpay.backend.idempotency.domain.IdempotencyRepository;
import com.flowpay.backend.refund.domain.Refund;
import com.flowpay.backend.refund.domain.RefundReason;
import com.flowpay.backend.refund.domain.RefundStatus;
import com.flowpay.backend.testing.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;

@SpringBootTest
@ActiveProfiles("test")
class PrepareRefundIntegrationTest extends PostgresIntegrationTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-31T08:00:00Z");
    private static final Money PAYMENT_AMOUNT = Money.of(1_000L, "USD");

    @MockitoBean
    private RefundProviderPort refundProvider;

    @Autowired
    private PrepareRefundService service;

    @Autowired
    private RefundRepository refundRepository;

    @Autowired
    private IdempotencyRepository idempotencyRepository;

    @Autowired
    private IdempotencyCompletionService completionService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanData() {
        dropRefundInsertFailureTrigger();
        jdbcTemplate.update("""
                TRUNCATE TABLE refunds, idempotency_records, payment_transactions,
                    payment_intents, merchant_members, merchant_api_keys, refresh_tokens,
                    merchants, users RESTART IDENTITY CASCADE
                """);
    }

    @AfterEach
    void verifyProviderBoundaryAndCleanTrigger() {
        dropRefundInsertFailureTrigger();
        verifyNoInteractions(refundProvider);
    }

    @Test
    void newRequestShouldCommitIdempotencyReservationAndProcessingRefundTogether() {
        PaymentFixture fixture = insertRefundablePayment("new");

        PrepareRefundResult result = service.prepare(command(
                fixture.merchantPublicId(),
                fixture.paymentPublicId(),
                "refund-new",
                400L,
                " Customer request "
        ));

        assertThat(result.decision()).isEqualTo(IdempotencyAcquisitionDecision.NEW);
        assertThat(result.replayResponse()).isNull();
        assertThat(result.prepared()).satisfies(prepared -> {
            assertThat(prepared.idempotencyExecutionId()).isPositive();
            assertThat(prepared.merchantInternalId()).isEqualTo(fixture.merchantId());
            assertThat(prepared.refundPublicId()).startsWith("re_").hasSize(29);
            assertThat(prepared.paymentPublicId()).isEqualTo(fixture.paymentPublicId());
            assertThat(prepared.amount()).isEqualTo(Money.of(400L, "USD"));
            assertThat(prepared.reason()).isEqualTo(RefundReason.of("Customer request"));
            assertThat(prepared.provider()).isEqualTo("SIMULATOR");
            assertThat(prepared.providerTransactionId()).isEqualTo(
                    "provider_charge_new"
            );
        });

        Refund refund = refundRepository.findByPublicIdAndMerchantId(
                result.prepared().refundPublicId(),
                fixture.merchantId()
        ).orElseThrow();
        assertThat(refund.paymentIntentId()).isEqualTo(fixture.paymentInternalId());
        assertThat(refund.status()).isEqualTo(RefundStatus.PROCESSING);
        assertThat(refund.amount()).isEqualTo(Money.of(400L, "USD"));
        assertThat(refund.provider()).isEqualTo("SIMULATOR");
        assertThat(refund.providerRefundId()).isNull();
        assertThat(refund.failure()).isNull();
        assertPaymentCapacity(fixture, 400L);

        IdempotencyRecord record = requireIdempotency(fixture, "refund-new");
        assertThat(record.internalId()).isEqualTo(
                result.prepared().idempotencyExecutionId()
        );
        assertThat(record.isProcessing()).isTrue();
        assertThat(record.resourceType()).isNull();
        assertThat(record.resourcePublicId()).isNull();
        assertThat(countRefunds(fixture.merchantId())).isEqualTo(1);
    }

    @Test
    void replayShouldReturnStoredResponseWithoutSecondReservationOrRefund() {
        PaymentFixture fixture = insertRefundablePayment("replay");
        PrepareRefundCommand command = command(
                fixture.merchantPublicId(),
                fixture.paymentPublicId(),
                "refund-replay",
                300L,
                null
        );
        PreparedRefund prepared = service.prepare(command).prepared();
        String storedPayload = "{\"data\":{\"id\":\"" + prepared.refundPublicId() + "\"}}";
        completionService.complete(new IdempotencyCompletionCommand(
                prepared.idempotencyExecutionId(),
                "REFUND",
                prepared.refundPublicId(),
                201,
                storedPayload
        ));

        PrepareRefundResult replay = service.prepare(command);

        assertThat(replay.decision()).isEqualTo(IdempotencyAcquisitionDecision.REPLAY);
        assertThat(replay.prepared()).isNull();
        assertThat(replay.replayResponse().resourceType()).isEqualTo("REFUND");
        assertThat(replay.replayResponse().resourcePublicId()).isEqualTo(
                prepared.refundPublicId()
        );
        assertThat(replay.replayResponse().httpStatus()).isEqualTo(201);
        assertThat(replay.replayResponse().responsePayload())
                .isEqualToIgnoringWhitespace(storedPayload);
        assertPaymentCapacity(fixture, 300L);
        assertThat(countRefunds(fixture.merchantId())).isEqualTo(1);
    }

    @Test
    void inProgressShouldNotReserveOrCreateAgain() {
        PaymentFixture fixture = insertRefundablePayment("progress");
        PrepareRefundCommand command = command(
                fixture.merchantPublicId(),
                fixture.paymentPublicId(),
                "refund-progress",
                250L,
                "Customer request"
        );
        service.prepare(command);

        PrepareRefundResult duplicate = service.prepare(command);

        assertThat(duplicate.decision()).isEqualTo(
                IdempotencyAcquisitionDecision.IN_PROGRESS
        );
        assertThat(duplicate.prepared()).isNull();
        assertThat(duplicate.replayResponse()).isNull();
        assertPaymentCapacity(fixture, 250L);
        assertThat(countRefunds(fixture.merchantId())).isEqualTo(1);
    }

    @Test
    void reusedKeyShouldNotReserveOrCreateForChangedRequest() {
        PaymentFixture fixture = insertRefundablePayment("reused");
        service.prepare(command(
                fixture.merchantPublicId(),
                fixture.paymentPublicId(),
                "refund-reused",
                200L,
                "Customer request"
        ));

        PrepareRefundResult reused = service.prepare(command(
                fixture.merchantPublicId(),
                fixture.paymentPublicId(),
                "refund-reused",
                201L,
                "Customer request"
        ));

        assertThat(reused.decision()).isEqualTo(IdempotencyAcquisitionDecision.KEY_REUSED);
        assertThat(reused.prepared()).isNull();
        assertThat(reused.replayResponse()).isNull();
        assertPaymentCapacity(fixture, 200L);
        assertThat(countRefunds(fixture.merchantId())).isEqualTo(1);
    }

    @Test
    void reservationFailureShouldRollbackNewIdempotencyAndRefund() {
        PaymentFixture fixture = insertRefundablePayment("capacity_failure");

        assertThatThrownBy(() -> service.prepare(command(
                fixture.merchantPublicId(),
                fixture.paymentPublicId(),
                "refund-capacity-failure",
                1_001L,
                null
        ))).isInstanceOfSatisfying(ApiException.class, exception ->
                assertThat(exception.code()).isEqualTo(
                        ErrorCode.REFUND_AMOUNT_EXCEEDS_AVAILABLE
                )
        );

        assertPaymentCapacity(fixture, 0L);
        assertThat(countRefunds(fixture.merchantId())).isZero();
        assertThat(findIdempotency(fixture, "refund-capacity-failure")).isEmpty();
    }

    @Test
    void refundPersistenceFailureShouldRollbackReservationAndNewIdempotency() {
        PaymentFixture fixture = insertRefundablePayment("persistence_failure");
        createRefundInsertFailureTrigger();

        assertThatThrownBy(() -> service.prepare(command(
                fixture.merchantPublicId(),
                fixture.paymentPublicId(),
                "refund-persistence-failure",
                350L,
                null
        ))).isInstanceOf(DataAccessException.class);

        assertPaymentCapacity(fixture, 0L);
        assertThat(countRefunds(fixture.merchantId())).isZero();
        assertThat(findIdempotency(fixture, "refund-persistence-failure")).isEmpty();
    }

    @Test
    void crossMerchantPaymentShouldBehaveAsNotFoundAndRollbackIdempotency() {
        PaymentFixture owner = insertRefundablePayment("owner");
        long otherMerchantId = insertMerchant("mrc_refund_other");

        assertThatThrownBy(() -> service.prepare(command(
                "mrc_refund_other",
                owner.paymentPublicId(),
                "refund-cross-merchant",
                100L,
                null
        ))).isInstanceOfSatisfying(ApiException.class, exception -> {
            assertThat(exception.code()).isEqualTo(ErrorCode.PAYMENT_NOT_FOUND);
            assertThat(exception.status().value()).isEqualTo(404);
        });

        assertPaymentCapacity(owner, 0L);
        assertThat(countRefunds(otherMerchantId)).isZero();
        assertThat(idempotencyRepository.findByScope(
                otherMerchantId,
                IdempotencyOperation.REFUND_CREATE,
                IdempotencyKey.of("refund-cross-merchant")
        )).isEmpty();
    }

    @Test
    void paymentRowLockShouldBeReleasedBeforePreparationReturns() throws Exception {
        PaymentFixture fixture = insertRefundablePayment("lock_release");
        service.prepare(command(
                fixture.merchantPublicId(),
                fixture.paymentPublicId(),
                "refund-lock-release",
                100L,
                null
        ));
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<Boolean> locked = executor.submit(() -> transaction.execute(status ->
                    Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                            """
                            SELECT true
                            FROM payment_intents
                            WHERE public_id = ? AND merchant_id = ?
                            FOR UPDATE
                            """,
                            Boolean.class,
                            fixture.paymentPublicId(),
                            fixture.merchantId()
                    ))
            ));

            assertThat(locked.get(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private PaymentFixture insertRefundablePayment(String suffix) {
        String merchantPublicId = "mrc_refund_" + suffix;
        long merchantId = insertMerchant(merchantPublicId);
        String paymentPublicId = "pi_refund_" + suffix;
        Long paymentInternalId = jdbcTemplate.queryForObject(
                """
                INSERT INTO payment_intents (
                    public_id, merchant_id, amount_minor, currency, status,
                    created_at, updated_at, version
                )
                VALUES (?, ?, ?, 'USD', 'SUCCEEDED', ?, ?, 0)
                RETURNING id
                """,
                Long.class,
                paymentPublicId,
                merchantId,
                PAYMENT_AMOUNT.amountMinor(),
                CREATED_AT.atOffset(ZoneOffset.UTC),
                CREATED_AT.plusSeconds(2).atOffset(ZoneOffset.UTC)
        );
        jdbcTemplate.update(
                """
                INSERT INTO payment_transactions (
                    public_id, payment_intent_id, attempt_no, provider,
                    provider_transaction_id, status, started_at, completed_at,
                    created_at, updated_at, version
                )
                VALUES (?, ?, 1, 'SIMULATOR', ?, 'SUCCEEDED', ?, ?, ?, ?, 0)
                """,
                "ptxn_refund_" + suffix,
                paymentInternalId,
                "provider_charge_" + suffix,
                CREATED_AT.plusSeconds(3).atOffset(ZoneOffset.UTC),
                CREATED_AT.plusSeconds(4).atOffset(ZoneOffset.UTC),
                CREATED_AT.plusSeconds(3).atOffset(ZoneOffset.UTC),
                CREATED_AT.plusSeconds(4).atOffset(ZoneOffset.UTC)
        );
        return new PaymentFixture(
                merchantId,
                merchantPublicId,
                paymentInternalId,
                paymentPublicId
        );
    }

    private long insertMerchant(String publicId) {
        Long id = jdbcTemplate.queryForObject(
                """
                INSERT INTO merchants (public_id, name, status, created_at, updated_at, version)
                VALUES (?, ?, 'ACTIVE', ?, ?, 0)
                RETURNING id
                """,
                Long.class,
                publicId,
                "Refund Preparation Store",
                CREATED_AT.atOffset(ZoneOffset.UTC),
                CREATED_AT.atOffset(ZoneOffset.UTC)
        );
        return id;
    }

    private static PrepareRefundCommand command(
            String merchantPublicId,
            String paymentPublicId,
            String idempotencyKey,
            long amountMinor,
            String reason
    ) {
        return new PrepareRefundCommand(
                new MerchantApiPrincipal(merchantPublicId, "key_refund_prepare"),
                IdempotencyKey.of(idempotencyKey),
                paymentPublicId,
                amountMinor,
                RefundReason.of(reason)
        );
    }

    private void assertPaymentCapacity(PaymentFixture fixture, long expectedReserved) {
        Map<String, Object> payment = jdbcTemplate.queryForMap(
                """
                SELECT status, refunded_amount_minor, refund_reserved_minor, currency
                FROM payment_intents
                WHERE id = ?
                """,
                fixture.paymentInternalId()
        );
        assertThat(payment.get("status")).isEqualTo("SUCCEEDED");
        assertThat(((Number) payment.get("refunded_amount_minor")).longValue()).isZero();
        assertThat(((Number) payment.get("refund_reserved_minor")).longValue())
                .isEqualTo(expectedReserved);
        assertThat(((String) payment.get("currency")).trim()).isEqualTo("USD");
    }

    private IdempotencyRecord requireIdempotency(PaymentFixture fixture, String key) {
        return findIdempotency(fixture, key).orElseThrow();
    }

    private java.util.Optional<IdempotencyRecord> findIdempotency(
            PaymentFixture fixture,
            String key
    ) {
        return idempotencyRepository.findByScope(
                fixture.merchantId(),
                IdempotencyOperation.REFUND_CREATE,
                IdempotencyKey.of(key)
        );
    }

    private int countRefunds(long merchantId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM refunds WHERE merchant_id = ?",
                Integer.class,
                merchantId
        );
        return count == null ? 0 : count;
    }

    private void createRefundInsertFailureTrigger() {
        jdbcTemplate.execute("""
                CREATE OR REPLACE FUNCTION fail_refund_insert() RETURNS trigger AS $$
                BEGIN
                    RAISE EXCEPTION 'forced refund insert failure';
                END;
                $$ LANGUAGE plpgsql
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER fail_refund_insert_trigger
                BEFORE INSERT ON refunds
                FOR EACH ROW EXECUTE FUNCTION fail_refund_insert()
                """);
    }

    private void dropRefundInsertFailureTrigger() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS fail_refund_insert_trigger ON refunds");
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS fail_refund_insert()");
    }

    private record PaymentFixture(
            long merchantId,
            String merchantPublicId,
            long paymentInternalId,
            String paymentPublicId
    ) {
    }
}
