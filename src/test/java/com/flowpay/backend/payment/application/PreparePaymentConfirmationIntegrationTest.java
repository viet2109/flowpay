package com.flowpay.backend.payment.application;

import com.flowpay.backend.common.error.ApiException;
import com.flowpay.backend.common.error.ErrorCode;
import com.flowpay.backend.common.money.Money;
import com.flowpay.backend.common.security.MerchantApiPrincipal;
import com.flowpay.backend.payment.domain.PaymentIntent;
import com.flowpay.backend.payment.domain.PaymentStatus;
import com.flowpay.backend.payment.domain.PaymentTransaction;
import com.flowpay.backend.payment.domain.PaymentTransactionStatus;
import com.flowpay.backend.testing.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class PreparePaymentConfirmationIntegrationTest extends PostgresIntegrationTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-29T08:00:00Z");
    private static final MerchantApiPrincipal PRINCIPAL = new MerchantApiPrincipal(
            "mrc_prepare_integration",
            "key_prepare_integration"
    );

    @Autowired
    private PreparePaymentConfirmationService service;

    @Autowired
    private PaymentIntentRepository paymentIntentRepository;

    @Autowired
    private PaymentTransactionRepository paymentTransactionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanData() {
        jdbcTemplate.update(
                "TRUNCATE TABLE payment_transactions, payment_intents, merchant_members, "
                        + "merchant_api_keys, refresh_tokens, merchants, users RESTART IDENTITY CASCADE"
        );
    }

    @Test
    void shouldCommitProcessingIntentAndFirstAttemptBeforeReturning() {
        PaymentIntent payment = insertCreatedPayment("pi_prepare_commit");

        PreparedPaymentConfirmation result = service.prepare(command(payment.publicId()));

        PaymentIntent reloadedPayment = paymentIntentRepository
                .findByPublicIdAndMerchantId(payment.publicId(), payment.merchantId())
                .orElseThrow();
        List<PaymentTransaction> attempts = paymentTransactionRepository
                .findByPaymentIntentId(payment.internalId());

        assertThat(reloadedPayment.status()).isEqualTo(PaymentStatus.PROCESSING);
        assertThat(reloadedPayment.version()).isEqualTo(1L);
        assertThat(attempts).singleElement().satisfies(attempt -> {
            assertThat(attempt.publicId()).isEqualTo(result.transactionPublicId());
            assertThat(attempt.attemptNo()).isEqualTo(1);
            assertThat(attempt.provider()).isEqualTo("SIMULATOR");
            assertThat(attempt.status()).isEqualTo(PaymentTransactionStatus.PROCESSING);
            assertThat(attempt.completedAt()).isNull();
        });
        assertThat(result.paymentPublicId()).isEqualTo(payment.publicId());
        assertThat(result.transactionPublicId()).startsWith("ptxn_").hasSize(31);
        assertThat(result.amountMinor()).isEqualTo(50_000L);
        assertThat(result.currency()).isEqualTo("VND");
        assertThat(result.provider()).isEqualTo("SIMULATOR");
    }

    @Test
    void concurrentPreparationsShouldProduceExactlyOneFirstAttempt() throws Exception {
        PaymentIntent payment = insertCreatedPayment("pi_prepare_concurrent");
        PreparePaymentConfirmationCommand command = command(payment.publicId());
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<PreparationOutcome> first = executor.submit(
                    () -> prepareConcurrently(command, ready, start)
            );
            Future<PreparationOutcome> second = executor.submit(
                    () -> prepareConcurrently(command, ready, start)
            );
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(List.of(
                    first.get(20, TimeUnit.SECONDS),
                    second.get(20, TimeUnit.SECONDS)
            )).containsExactlyInAnyOrder(
                    PreparationOutcome.SUCCESS,
                    PreparationOutcome.INVALID_STATE
            );
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        PaymentIntent reloadedPayment = paymentIntentRepository
                .findByPublicIdAndMerchantId(payment.publicId(), payment.merchantId())
                .orElseThrow();
        List<PaymentTransaction> attempts = paymentTransactionRepository
                .findByPaymentIntentId(payment.internalId());

        assertThat(reloadedPayment.status()).isEqualTo(PaymentStatus.PROCESSING);
        assertThat(reloadedPayment.version()).isEqualTo(1L);
        assertThat(attempts).singleElement()
                .extracting(PaymentTransaction::attemptNo)
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM payment_transactions "
                        + "WHERE payment_intent_id = ? AND attempt_no = 1",
                Integer.class,
                payment.internalId()
        )).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(
            value = PaymentStatus.class,
            names = {"PROCESSING", "SUCCEEDED", "FAILED"}
    )
    void shouldRejectPaymentsThatAreNotCreated(PaymentStatus status) {
        PaymentIntent payment = insertCreatedPayment(
                "pi_prepare_invalid_" + status.name().toLowerCase()
        );
        payment.startProcessing(CREATED_AT.plusSeconds(1));
        if (status == PaymentStatus.SUCCEEDED) {
            payment.markSucceeded(CREATED_AT.plusSeconds(2));
        } else if (status == PaymentStatus.FAILED) {
            payment.markFailed(CREATED_AT.plusSeconds(2));
        }
        paymentIntentRepository.save(payment);

        assertThatThrownBy(() -> service.prepare(command(payment.publicId())))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.code()).isEqualTo(ErrorCode.PAYMENT_INVALID_STATE)
                );

        assertThat(paymentTransactionRepository.findByPaymentIntentId(payment.internalId()))
                .isEmpty();
    }

    private PreparationOutcome prepareConcurrently(
            PreparePaymentConfirmationCommand command,
            CountDownLatch ready,
            CountDownLatch start
    ) throws InterruptedException {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("concurrent preparation did not start in time");
        }
        try {
            service.prepare(command);
            return PreparationOutcome.SUCCESS;
        } catch (ApiException exception) {
            assertThat(exception.code()).isEqualTo(ErrorCode.PAYMENT_INVALID_STATE);
            return PreparationOutcome.INVALID_STATE;
        }
    }

    private PaymentIntent insertCreatedPayment(String publicId) {
        long merchantId = insertActiveMerchant();
        return paymentIntentRepository.save(PaymentIntent.create(
                publicId,
                merchantId,
                "ORDER-PREPARE",
                "Prepare confirmation",
                Money.of(50_000L, "VND"),
                CREATED_AT
        ));
    }

    private long insertActiveMerchant() {
        Long id = jdbcTemplate.queryForObject(
                """
                INSERT INTO merchants (public_id, name, status, created_at, updated_at, version)
                VALUES (?, ?, 'ACTIVE', ?, ?, 0)
                RETURNING id
                """,
                Long.class,
                PRINCIPAL.merchantPublicId(),
                "Prepare Confirmation Store",
                CREATED_AT.atOffset(ZoneOffset.UTC),
                CREATED_AT.atOffset(ZoneOffset.UTC)
        );
        return id;
    }

    private static PreparePaymentConfirmationCommand command(String paymentPublicId) {
        return new PreparePaymentConfirmationCommand(PRINCIPAL, paymentPublicId);
    }

    private enum PreparationOutcome {
        SUCCESS,
        INVALID_STATE
    }
}
