package com.flowpay.backend.payment.application;

import com.flowpay.backend.common.error.ApiException;
import com.flowpay.backend.common.error.ErrorCode;
import com.flowpay.backend.common.money.Money;
import com.flowpay.backend.common.security.MerchantApiPrincipal;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(ConfirmPaymentIntegrationTest.ProviderTestConfiguration.class)
class ConfirmPaymentIntegrationTest extends PostgresIntegrationTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-29T08:00:00Z");

    @Autowired
    private ConfirmPaymentService service;

    @Autowired
    private PaymentIntentRepository paymentIntentRepository;

    @Autowired
    private PaymentTransactionRepository paymentTransactionRepository;

    @Autowired
    private InspectingPaymentProvider paymentProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanData() {
        jdbcTemplate.update(
                "TRUNCATE TABLE outbox_events, ledger_entries, ledger_transactions, "
                        + "ledger_accounts, "
                        + "payment_transactions, payment_intents, merchant_members, "
                        + "merchant_api_keys, refresh_tokens, merchants, users "
                        + "RESTART IDENTITY CASCADE"
        );
        paymentProvider.reset();
    }

    @ParameterizedTest
    @MethodSource("providerOutcomes")
    void shouldCommitTx1CallProviderWithoutTransactionAndCommitTx2(
            ProviderOutcome outcome,
            PaymentStatus expectedPaymentStatus,
            PaymentTransactionStatus expectedTransactionStatus
    ) {
        String suffix = outcome.name().toLowerCase();
        String merchantPublicId = "mrc_confirm_" + suffix;
        PaymentIntent createdPayment = insertCreatedPayment(
                merchantPublicId,
                "pi_confirm_" + suffix
        );
        paymentProvider.respondWith(outcome);

        FinalizedPaymentConfirmation result = service.confirm(
                new ConfirmPaymentCommand(
                        new MerchantApiPrincipal(merchantPublicId, "key_confirm_" + suffix),
                        createdPayment.publicId()
                )
        );

        PaymentIntent payment = paymentIntentRepository
                .findByPublicId(createdPayment.publicId())
                .orElseThrow();
        PaymentTransaction transaction = paymentTransactionRepository
                .findByPublicId(result.transactionPublicId())
                .orElseThrow();

        assertThat(paymentProvider.invocationCount()).isEqualTo(1);
        assertThat(paymentProvider.transactionActive()).isFalse();
        assertThat(paymentProvider.observedPaymentStatus())
                .isEqualTo(PaymentStatus.PROCESSING.name());
        assertThat(paymentProvider.observedTransactionStatus())
                .isEqualTo(PaymentTransactionStatus.PROCESSING.name());
        assertThat(paymentProvider.observedTransactionPublicId())
                .isEqualTo(transaction.publicId());
        assertThat(paymentProvider.lastRequest().paymentPublicReference())
                .isEqualTo(payment.publicId());
        assertThat(paymentProvider.lastRequest().amountMinor()).isEqualTo(50_000L);
        assertThat(paymentProvider.lastRequest().currency()).isEqualTo("VND");

        assertThat(payment.status()).isEqualTo(expectedPaymentStatus);
        assertThat(transaction.status()).isEqualTo(expectedTransactionStatus);
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
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM payment_transactions "
                        + "WHERE payment_intent_id = ? AND attempt_no = 1",
                Integer.class,
                payment.internalId()
        )).isEqualTo(1);
        int expectedOutboxCount = outcome == ProviderOutcome.SUCCESS ? 1 : 0;
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM outbox_events "
                        + "WHERE event_type = 'payment.succeeded.v1'",
                Integer.class
        )).isEqualTo(expectedOutboxCount);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ledger_transactions",
                Integer.class
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ledger_entries",
                Integer.class
        )).isZero();
    }

    @Test
    void shouldNotCallProviderWhenPreparationFails() {
        String merchantPublicId = "mrc_confirm_rejected";
        PaymentIntent payment = insertCreatedPayment(
                merchantPublicId,
                "pi_confirm_rejected"
        );
        payment.startProcessing(CREATED_AT.plusSeconds(1));
        paymentIntentRepository.save(payment);
        paymentProvider.respondWith(ProviderOutcome.SUCCESS);

        assertThatThrownBy(() -> service.confirm(new ConfirmPaymentCommand(
                new MerchantApiPrincipal(merchantPublicId, "key_confirm_rejected"),
                payment.publicId()
        ))).isInstanceOfSatisfying(ApiException.class, exception -> {
            assertThat(exception.code()).isEqualTo(ErrorCode.PAYMENT_INVALID_STATE);
        });

        assertThat(paymentProvider.invocationCount()).isZero();
        assertThat(paymentTransactionRepository.findByPaymentIntentId(payment.internalId()))
                .isEmpty();
    }

    private PaymentIntent insertCreatedPayment(
            String merchantPublicId,
            String paymentPublicId
    ) {
        long merchantId = insertActiveMerchant(merchantPublicId);
        return paymentIntentRepository.save(PaymentIntent.create(
                paymentPublicId,
                merchantId,
                "ORDER-CONFIRM",
                "Confirm payment",
                Money.of(50_000L, "VND"),
                CREATED_AT
        ));
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
                "Confirm Payment Store",
                CREATED_AT.atOffset(ZoneOffset.UTC),
                CREATED_AT.atOffset(ZoneOffset.UTC)
        );
        return id;
    }

    private static Stream<Arguments> providerOutcomes() {
        return Stream.of(
                Arguments.of(
                        ProviderOutcome.SUCCESS,
                        PaymentStatus.SUCCEEDED,
                        PaymentTransactionStatus.SUCCEEDED
                ),
                Arguments.of(
                        ProviderOutcome.DECLINED,
                        PaymentStatus.FAILED,
                        PaymentTransactionStatus.FAILED
                ),
                Arguments.of(
                        ProviderOutcome.UNKNOWN,
                        PaymentStatus.PROCESSING,
                        PaymentTransactionStatus.UNKNOWN
                ),
                Arguments.of(
                        ProviderOutcome.TECHNICAL_FAILURE,
                        PaymentStatus.FAILED,
                        PaymentTransactionStatus.FAILED
                )
        );
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProviderTestConfiguration {

        @Bean
        @Primary
        InspectingPaymentProvider inspectingPaymentProvider(JdbcTemplate jdbcTemplate) {
            return new InspectingPaymentProvider(jdbcTemplate);
        }
    }

    static final class InspectingPaymentProvider implements PaymentProviderPort {

        private final JdbcTemplate jdbcTemplate;
        private ProviderOutcome outcome;
        private int invocationCount;
        private boolean transactionActive;
        private String observedPaymentStatus;
        private String observedTransactionStatus;
        private String observedTransactionPublicId;
        private PaymentProviderRequest lastRequest;

        private InspectingPaymentProvider(JdbcTemplate jdbcTemplate) {
            this.jdbcTemplate = jdbcTemplate;
        }

        void respondWith(ProviderOutcome outcome) {
            this.outcome = outcome;
        }

        void reset() {
            outcome = null;
            invocationCount = 0;
            transactionActive = false;
            observedPaymentStatus = null;
            observedTransactionStatus = null;
            observedTransactionPublicId = null;
            lastRequest = null;
        }

        @Override
        public PaymentProviderResult charge(PaymentProviderRequest request) {
            invocationCount++;
            lastRequest = request;
            transactionActive = TransactionSynchronizationManager
                    .isActualTransactionActive();
            observedPaymentStatus = jdbcTemplate.queryForObject(
                    "SELECT status FROM payment_intents WHERE public_id = ?",
                    String.class,
                    request.paymentPublicReference()
            );
            observedTransactionStatus = jdbcTemplate.queryForObject(
                    """
                    SELECT pt.status
                    FROM payment_transactions pt
                    JOIN payment_intents pi ON pi.id = pt.payment_intent_id
                    WHERE pi.public_id = ?
                    ORDER BY pt.attempt_no DESC
                    LIMIT 1
                    """,
                    String.class,
                    request.paymentPublicReference()
            );
            observedTransactionPublicId = jdbcTemplate.queryForObject(
                    """
                    SELECT pt.public_id
                    FROM payment_transactions pt
                    JOIN payment_intents pi ON pi.id = pt.payment_intent_id
                    WHERE pi.public_id = ?
                    ORDER BY pt.attempt_no DESC
                    LIMIT 1
                    """,
                    String.class,
                    request.paymentPublicReference()
            );
            return result(request, outcome);
        }

        private static PaymentProviderResult result(
                PaymentProviderRequest request,
                ProviderOutcome outcome
        ) {
            return switch (outcome) {
                case SUCCESS -> new PaymentProviderResult(
                        "SIMULATOR",
                        outcome,
                        "sim_" + request.paymentPublicReference(),
                        null,
                        null
                );
                case DECLINED -> new PaymentProviderResult(
                        "SIMULATOR",
                        outcome,
                        "sim_" + request.paymentPublicReference(),
                        "CARD_DECLINED",
                        "The provider declined the payment."
                );
                case UNKNOWN -> new PaymentProviderResult(
                        "SIMULATOR",
                        outcome,
                        null,
                        "PROVIDER_TIMEOUT",
                        "The provider outcome is unknown."
                );
                case TECHNICAL_FAILURE -> new PaymentProviderResult(
                        "SIMULATOR",
                        outcome,
                        null,
                        "PROVIDER_UNAVAILABLE",
                        "The provider operation did not complete."
                );
            };
        }

        int invocationCount() {
            return invocationCount;
        }

        boolean transactionActive() {
            return transactionActive;
        }

        String observedPaymentStatus() {
            return observedPaymentStatus;
        }

        String observedTransactionStatus() {
            return observedTransactionStatus;
        }

        String observedTransactionPublicId() {
            return observedTransactionPublicId;
        }

        PaymentProviderRequest lastRequest() {
            return lastRequest;
        }
    }
}
