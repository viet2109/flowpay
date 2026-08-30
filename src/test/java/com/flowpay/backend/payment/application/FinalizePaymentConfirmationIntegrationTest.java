package com.flowpay.backend.payment.application;

import com.flowpay.backend.common.money.Money;
import com.flowpay.backend.payment.domain.PaymentIntent;
import com.flowpay.backend.payment.domain.PaymentStatus;
import com.flowpay.backend.payment.domain.PaymentTransaction;
import com.flowpay.backend.payment.domain.PaymentTransactionStatus;
import com.flowpay.backend.payment.domain.ProviderOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class FinalizePaymentConfirmationIntegrationTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-29T08:00:00Z");
    private static final Instant STARTED_AT = Instant.parse("2026-08-29T08:00:05Z");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16.15-alpine");

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
        jdbcTemplate.update(
                "TRUNCATE TABLE payment_transactions, payment_intents, merchant_members, "
                        + "merchant_api_keys, refresh_tokens, merchants, users RESTART IDENTITY CASCADE"
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
                expectedTransactionStatus
        ));
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
