package com.flowpay.backend.payment.application;

import com.flowpay.backend.common.error.ApiException;
import com.flowpay.backend.common.error.ErrorCode;
import com.flowpay.backend.common.money.Money;
import com.flowpay.backend.payment.domain.PaymentIntent;
import com.flowpay.backend.payment.domain.PaymentStatus;
import com.flowpay.backend.payment.domain.PaymentTransaction;
import com.flowpay.backend.testing.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class PaymentRefundApiIntegrationTest extends PostgresIntegrationTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-30T08:00:00Z");
    private static final Money PAYMENT_AMOUNT = Money.of(1_000L, "USD");

    @Autowired
    private PaymentRefundApi paymentRefundApi;

    @Autowired
    private PaymentIntentRepository paymentIntentRepository;

    @Autowired
    private PaymentTransactionRepository paymentTransactionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanPaymentData() {
        jdbcTemplate.update(
                "TRUNCATE TABLE payment_transactions, payment_intents, merchants RESTART IDENTITY CASCADE"
        );
    }

    @Test
    void shouldReserveCapacityAndReturnOriginalSuccessfulProviderReference() {
        long merchantId = insertMerchant("mrc_refund_reserve");
        PaymentIntent payment = savePayment("pi_refund_reserve", merchantId, PaymentStatus.SUCCEEDED);
        saveSucceededTransaction(payment, 1, "provider_charge_original");
        saveFailedTransaction(payment, 2, "provider_attempt_later");

        PaymentRefundReservation reservation = paymentRefundApi.reserveRefund(
                merchantId,
                payment.publicId(),
                400L
        );

        assertThat(reservation.paymentInternalId()).isEqualTo(payment.internalId());
        assertThat(reservation.paymentPublicId()).isEqualTo(payment.publicId());
        assertThat(reservation.merchantInternalId()).isEqualTo(merchantId);
        assertThat(reservation.amount()).isEqualTo(Money.of(400L, "USD"));
        assertThat(reservation.provider()).isEqualTo("SIMULATOR");
        assertThat(reservation.providerTransactionId()).isEqualTo("provider_charge_original");
        assertCapacity(payment.publicId(), merchantId, 0L, 400L, PaymentStatus.SUCCEEDED);
    }

    @Test
    void shouldReserveFromPartiallyRefundedPaymentAndAcceptExactAvailableAmount() {
        long merchantId = insertMerchant("mrc_refund_partial");
        PaymentIntent payment = savePayment(
                "pi_refund_partial",
                merchantId,
                PaymentStatus.PARTIALLY_REFUNDED
        );
        saveSucceededTransaction(payment, 1, "provider_charge_partial");

        PaymentRefundReservation reservation = paymentRefundApi.reserveRefund(
                merchantId,
                payment.publicId(),
                750L
        );

        assertThat(reservation.amount()).isEqualTo(Money.of(750L, "USD"));
        assertCapacity(
                payment.publicId(),
                merchantId,
                250L,
                750L,
                PaymentStatus.PARTIALLY_REFUNDED
        );
    }

    @Test
    void shouldRejectInvalidPaymentStatesAndAmounts() {
        long merchantId = insertMerchant("mrc_refund_rejected");
        for (PaymentStatus status : List.of(
                PaymentStatus.CREATED,
                PaymentStatus.PROCESSING,
                PaymentStatus.FAILED,
                PaymentStatus.REFUNDED
        )) {
            PaymentIntent payment = savePayment(
                    "pi_refund_" + status.name().toLowerCase(),
                    merchantId,
                    status
            );

            assertThatThrownBy(() -> paymentRefundApi.reserveRefund(
                    merchantId,
                    payment.publicId(),
                    1L
            )).isInstanceOfSatisfying(ApiException.class, exception -> {
                assertThat(exception.code()).isEqualTo(ErrorCode.REFUND_INVALID_PAYMENT_STATE);
                assertThat(exception.status().value()).isEqualTo(409);
            });
        }

        PaymentIntent succeeded = savePayment(
                "pi_refund_invalid_amount",
                merchantId,
                PaymentStatus.SUCCEEDED
        );
        saveSucceededTransaction(succeeded, 1, "provider_charge_amount");

        assertThatThrownBy(() -> paymentRefundApi.reserveRefund(
                merchantId,
                succeeded.publicId(),
                0L
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("amountMinor must be positive");
        assertThatThrownBy(() -> paymentRefundApi.reserveRefund(
                merchantId,
                succeeded.publicId(),
                1_001L
        )).isInstanceOfSatisfying(ApiException.class, exception -> {
            assertThat(exception.code()).isEqualTo(
                    ErrorCode.REFUND_AMOUNT_EXCEEDS_AVAILABLE
            );
            assertThat(exception.status().value()).isEqualTo(409);
        });
        assertCapacity(
                succeeded.publicId(),
                merchantId,
                0L,
                0L,
                PaymentStatus.SUCCEEDED
        );
    }

    @Test
    void shouldCompleteAndReleaseReservedCapacityAtomically() {
        long merchantId = insertMerchant("mrc_refund_finalize");
        PaymentIntent payment = savePayment(
                "pi_refund_finalize",
                merchantId,
                PaymentStatus.SUCCEEDED
        );
        saveSucceededTransaction(payment, 1, "provider_charge_finalize");
        paymentRefundApi.reserveRefund(merchantId, payment.publicId(), 1_000L);

        paymentRefundApi.completeRefund(
                merchantId,
                payment.publicId(),
                payment.internalId(),
                Money.of(400L, "USD")
        );

        assertCapacity(
                payment.publicId(),
                merchantId,
                400L,
                600L,
                PaymentStatus.PARTIALLY_REFUNDED
        );

        paymentRefundApi.releaseRefund(
                merchantId,
                payment.publicId(),
                payment.internalId(),
                Money.of(200L, "USD")
        );
        assertCapacity(
                payment.publicId(),
                merchantId,
                400L,
                400L,
                PaymentStatus.PARTIALLY_REFUNDED
        );

        paymentRefundApi.completeRefund(
                merchantId,
                payment.publicId(),
                payment.internalId(),
                Money.of(400L, "USD")
        );
        assertCapacity(
                payment.publicId(),
                merchantId,
                800L,
                0L,
                PaymentStatus.PARTIALLY_REFUNDED
        );
    }

    @Test
    void shouldMarkPaymentRefundedWhenCompletionReachesOriginalAmount() {
        long merchantId = insertMerchant("mrc_refund_complete");
        PaymentIntent payment = savePayment(
                "pi_refund_complete",
                merchantId,
                PaymentStatus.SUCCEEDED
        );
        saveSucceededTransaction(payment, 1, "provider_charge_complete");
        paymentRefundApi.reserveRefund(merchantId, payment.publicId(), 1_000L);

        paymentRefundApi.completeRefund(
                merchantId,
                payment.publicId(),
                payment.internalId(),
                Money.of(1_000L, "USD")
        );

        assertCapacity(
                payment.publicId(),
                merchantId,
                1_000L,
                0L,
                PaymentStatus.REFUNDED
        );
    }

    @Test
    void shouldRejectMismatchedExpectedPaymentBeforeConsumingReservation() {
        long merchantId = insertMerchant("mrc_refund_expected_payment");
        PaymentIntent reservedPayment = savePayment(
                "pi_refund_expected_reserved",
                merchantId,
                PaymentStatus.SUCCEEDED
        );
        PaymentIntent differentPayment = savePayment(
                "pi_refund_expected_different",
                merchantId,
                PaymentStatus.SUCCEEDED
        );
        saveSucceededTransaction(
                reservedPayment,
                1,
                "provider_charge_expected_reserved"
        );
        paymentRefundApi.reserveRefund(merchantId, reservedPayment.publicId(), 300L);

        assertThatThrownBy(() -> paymentRefundApi.completeRefund(
                merchantId,
                reservedPayment.publicId(),
                differentPayment.internalId(),
                Money.of(300L, "USD")
        )).isInstanceOfSatisfying(ApiException.class, exception ->
                assertThat(exception.code()).isEqualTo(ErrorCode.PAYMENT_NOT_FOUND)
        );

        assertCapacity(
                reservedPayment.publicId(),
                merchantId,
                0L,
                300L,
                PaymentStatus.SUCCEEDED
        );
    }

    @Test
    void shouldHideCrossMerchantPaymentOwnership() {
        long ownerId = insertMerchant("mrc_refund_owner");
        long otherId = insertMerchant("mrc_refund_other");
        PaymentIntent payment = savePayment(
                "pi_refund_owned",
                ownerId,
                PaymentStatus.SUCCEEDED
        );

        assertThat(paymentRefundApi.requireOwnedPayment(ownerId, payment.publicId()))
                .isEqualTo(new OwnedPaymentSnapshot(payment.internalId(), payment.publicId()));
        assertThat(paymentRefundApi.requireOwnedPaymentByInternalId(
                ownerId,
                payment.internalId()
        )).isEqualTo(new OwnedPaymentSnapshot(payment.internalId(), payment.publicId()));
        assertThatThrownBy(() -> paymentRefundApi.requireOwnedPayment(
                otherId,
                payment.publicId()
        )).isInstanceOfSatisfying(ApiException.class, exception -> {
            assertThat(exception.code()).isEqualTo(ErrorCode.PAYMENT_NOT_FOUND);
            assertThat(exception.status().value()).isEqualTo(404);
        });
        assertThatThrownBy(() -> paymentRefundApi.requireOwnedPaymentByInternalId(
                otherId,
                payment.internalId()
        )).isInstanceOfSatisfying(ApiException.class, exception -> {
            assertThat(exception.code()).isEqualTo(ErrorCode.PAYMENT_NOT_FOUND);
            assertThat(exception.status().value()).isEqualTo(404);
        });
        assertThatThrownBy(() -> paymentRefundApi.reserveRefund(
                otherId,
                payment.publicId(),
                1L
        )).isInstanceOfSatisfying(ApiException.class, exception ->
                assertThat(exception.code()).isEqualTo(ErrorCode.PAYMENT_NOT_FOUND)
        );
    }

    @Test
    void shouldFailSafelyForMissingOrAmbiguousSuccessfulProviderData() {
        long merchantId = insertMerchant("mrc_refund_provider_data");
        PaymentIntent missing = savePayment(
                "pi_refund_missing_provider",
                merchantId,
                PaymentStatus.SUCCEEDED
        );
        saveSucceededTransaction(missing, 1, null);
        PaymentIntent ambiguous = savePayment(
                "pi_refund_ambiguous_provider",
                merchantId,
                PaymentStatus.SUCCEEDED
        );
        saveSucceededTransaction(ambiguous, 1, "provider_charge_one");
        saveSucceededTransaction(ambiguous, 2, "provider_charge_two");

        assertThatThrownBy(() -> paymentRefundApi.reserveRefund(
                merchantId,
                missing.publicId(),
                100L
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exactly one successful provider transaction");
        assertThatThrownBy(() -> paymentRefundApi.reserveRefund(
                merchantId,
                ambiguous.publicId(),
                100L
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exactly one successful provider transaction");
        assertCapacity(missing.publicId(), merchantId, 0L, 0L, PaymentStatus.SUCCEEDED);
        assertCapacity(ambiguous.publicId(), merchantId, 0L, 0L, PaymentStatus.SUCCEEDED);
    }

    @Test
    void shouldSerializeConcurrentReservationsAndPreventOverRefund() throws Exception {
        long merchantId = insertMerchant("mrc_refund_concurrent_over");
        PaymentIntent payment = savePayment(
                "pi_refund_concurrent_over",
                merchantId,
                PaymentStatus.SUCCEEDED
        );
        saveSucceededTransaction(payment, 1, "provider_charge_concurrent_over");

        List<ReservationAttempt> results = reserveConcurrently(
                merchantId,
                payment.publicId(),
                600L,
                600L
        );

        assertThat(results).filteredOn(ReservationAttempt::succeeded).hasSize(1);
        assertThat(results).filteredOn(result -> !result.succeeded()).singleElement()
                .satisfies(result -> assertThat(result.failure())
                        .isInstanceOfSatisfying(ApiException.class, exception ->
                                assertThat(exception.code()).isEqualTo(
                                        ErrorCode.REFUND_AMOUNT_EXCEEDS_AVAILABLE
                                )
                        ));
        assertCapacity(
                payment.publicId(),
                merchantId,
                0L,
                600L,
                PaymentStatus.SUCCEEDED
        );
        assertDatabaseInvariant(payment.internalId());
    }

    @Test
    void shouldSerializeAndAcceptConcurrentReservationsWhoseSumFits() throws Exception {
        long merchantId = insertMerchant("mrc_refund_concurrent_fit");
        PaymentIntent payment = savePayment(
                "pi_refund_concurrent_fit",
                merchantId,
                PaymentStatus.SUCCEEDED
        );
        saveSucceededTransaction(payment, 1, "provider_charge_concurrent_fit");

        List<ReservationAttempt> results = reserveConcurrently(
                merchantId,
                payment.publicId(),
                400L,
                600L
        );

        assertThat(results).allMatch(ReservationAttempt::succeeded);
        assertCapacity(
                payment.publicId(),
                merchantId,
                0L,
                1_000L,
                PaymentStatus.SUCCEEDED
        );
        assertDatabaseInvariant(payment.internalId());
    }

    private PaymentIntent savePayment(
            String publicId,
            long merchantId,
            PaymentStatus targetStatus
    ) {
        PaymentIntent payment = PaymentIntent.create(
                publicId,
                merchantId,
                null,
                null,
                PAYMENT_AMOUNT,
                CREATED_AT
        );
        if (targetStatus != PaymentStatus.CREATED) {
            payment.startProcessing(CREATED_AT.plusSeconds(1));
        }
        if (targetStatus == PaymentStatus.SUCCEEDED
                || targetStatus == PaymentStatus.PARTIALLY_REFUNDED
                || targetStatus == PaymentStatus.REFUNDED) {
            payment.markSucceeded(CREATED_AT.plusSeconds(2));
        } else if (targetStatus == PaymentStatus.FAILED) {
            payment.markFailed(CREATED_AT.plusSeconds(2));
        }
        if (targetStatus == PaymentStatus.PARTIALLY_REFUNDED) {
            payment.reserveRefund(Money.of(250L, "USD"), CREATED_AT.plusSeconds(3));
            payment.completeRefund(Money.of(250L, "USD"), CREATED_AT.plusSeconds(4));
        } else if (targetStatus == PaymentStatus.REFUNDED) {
            payment.reserveRefund(PAYMENT_AMOUNT, CREATED_AT.plusSeconds(3));
            payment.completeRefund(PAYMENT_AMOUNT, CREATED_AT.plusSeconds(4));
        }
        return paymentIntentRepository.save(payment);
    }

    private void saveSucceededTransaction(
            PaymentIntent payment,
            int attempt,
            String providerTransactionId
    ) {
        Instant startedAt = CREATED_AT.plusSeconds(10L + attempt);
        PaymentTransaction transaction = paymentTransactionRepository.save(
                PaymentTransaction.createProcessing(
                        "ptxn_" + payment.publicId().substring(3) + "_" + attempt,
                        payment.internalId(),
                        attempt,
                        "SIMULATOR",
                        startedAt
                )
        );
        transaction.markSucceeded(providerTransactionId, startedAt.plusSeconds(1));
        paymentTransactionRepository.save(transaction);
    }

    private void saveFailedTransaction(
            PaymentIntent payment,
            int attempt,
            String providerTransactionId
    ) {
        Instant startedAt = CREATED_AT.plusSeconds(10L + attempt);
        PaymentTransaction transaction = paymentTransactionRepository.save(
                PaymentTransaction.createProcessing(
                        "ptxn_" + payment.publicId().substring(3) + "_" + attempt,
                        payment.internalId(),
                        attempt,
                        "SIMULATOR",
                        startedAt
                )
        );
        transaction.markFailed(
                providerTransactionId,
                "DECLINED",
                "The later attempt failed.",
                startedAt.plusSeconds(1)
        );
        paymentTransactionRepository.save(transaction);
    }

    private List<ReservationAttempt> reserveConcurrently(
            long merchantId,
            String paymentPublicId,
            long firstAmount,
            long secondAmount
    ) throws InterruptedException, ExecutionException {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Future<ReservationAttempt>> futures = new ArrayList<>();
            for (long amount : List.of(firstAmount, secondAmount)) {
                futures.add(executor.submit(() -> {
                    start.await();
                    try {
                        paymentRefundApi.reserveRefund(merchantId, paymentPublicId, amount);
                        return ReservationAttempt.success();
                    } catch (RuntimeException exception) {
                        return ReservationAttempt.failure(exception);
                    }
                }));
            }
            start.countDown();
            List<ReservationAttempt> results = new ArrayList<>();
            for (Future<ReservationAttempt> future : futures) {
                results.add(future.get());
            }
            return results;
        }
    }

    private void assertCapacity(
            String paymentPublicId,
            long merchantId,
            long refunded,
            long reserved,
            PaymentStatus status
    ) {
        PaymentIntent payment = paymentIntentRepository
                .findByPublicIdAndMerchantId(paymentPublicId, merchantId)
                .orElseThrow();
        assertThat(payment.refundedAmount()).isEqualTo(Money.of(refunded, "USD"));
        assertThat(payment.refundReservedAmount()).isEqualTo(Money.of(reserved, "USD"));
        assertThat(payment.status()).isEqualTo(status);
    }

    private void assertDatabaseInvariant(long paymentInternalId) {
        Boolean valid = jdbcTemplate.queryForObject(
                """
                SELECT refunded_amount_minor + refund_reserved_minor <= amount_minor
                FROM payment_intents
                WHERE id = ?
                """,
                Boolean.class,
                paymentInternalId
        );
        assertThat(valid).isTrue();
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
                "Refund Store",
                CREATED_AT.atOffset(ZoneOffset.UTC),
                CREATED_AT.atOffset(ZoneOffset.UTC)
        );
        return id;
    }

    private record ReservationAttempt(boolean succeeded, RuntimeException failure) {

        private static ReservationAttempt success() {
            return new ReservationAttempt(true, null);
        }

        private static ReservationAttempt failure(RuntimeException exception) {
            return new ReservationAttempt(false, exception);
        }
    }
}
