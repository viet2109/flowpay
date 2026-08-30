package com.flowpay.backend.payment.infrastructure.persistence;

import com.flowpay.backend.common.money.Money;
import com.flowpay.backend.payment.application.PaymentIntentRepository;
import com.flowpay.backend.payment.application.PaymentTransactionRepository;
import com.flowpay.backend.payment.domain.PaymentIntent;
import com.flowpay.backend.payment.domain.PaymentStatus;
import com.flowpay.backend.payment.domain.PaymentTransaction;
import com.flowpay.backend.payment.domain.PaymentTransactionStatus;
import com.flowpay.backend.testing.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class PaymentPersistenceTest extends PostgresIntegrationTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-30T08:00:00Z");

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
    void shouldPersistAndReloadPaymentIntentWithMoneyStatusAndOwnership() {
        long merchantId = insertMerchant("mrc_payment_owner");
        long otherMerchantId = insertMerchant("mrc_payment_other");
        PaymentIntent saved = paymentIntentRepository.save(PaymentIntent.create(
                "pi_persistence",
                merchantId,
                "order-1001",
                "Persistence mapping",
                Money.of(12_345L, "usd"),
                CREATED_AT
        ));

        saved.startProcessing(CREATED_AT.plusSeconds(5));
        PaymentIntent updated = paymentIntentRepository.save(saved);
        PaymentIntent reloaded = paymentIntentRepository
                .findByPublicIdAndMerchantId(saved.publicId(), merchantId)
                .orElseThrow();

        assertThat(saved.internalId()).isPositive();
        assertThat(saved.version()).isZero();
        assertThat(updated.version()).isEqualTo(1L);
        assertThat(reloaded.internalId()).isEqualTo(saved.internalId());
        assertThat(reloaded.publicId()).isEqualTo("pi_persistence");
        assertThat(reloaded.merchantId()).isEqualTo(merchantId);
        assertThat(reloaded.merchantOrderId()).isEqualTo("order-1001");
        assertThat(reloaded.description()).isEqualTo("Persistence mapping");
        assertThat(reloaded.amount()).isEqualTo(Money.of(12_345L, "USD"));
        assertThat(reloaded.refundedAmount()).isEqualTo(Money.of(0L, "USD"));
        assertThat(reloaded.refundReservedAmount()).isEqualTo(Money.of(0L, "USD"));
        assertThat(reloaded.status()).isEqualTo(PaymentStatus.PROCESSING);
        assertThat(reloaded.createdAt()).isEqualTo(CREATED_AT);
        assertThat(reloaded.updatedAt()).isEqualTo(CREATED_AT.plusSeconds(5));
        assertThat(paymentIntentRepository.findByPublicIdAndMerchantId(
                saved.publicId(),
                otherMerchantId
        )).isEmpty();
    }

    @Test
    void shouldSearchOnlyOwningMerchantWithNewestIntentFirst() {
        long merchantId = insertMerchant("mrc_payment_search");
        long otherMerchantId = insertMerchant("mrc_payment_search_other");
        paymentIntentRepository.save(newIntent(
                "pi_older",
                merchantId,
                CREATED_AT
        ));
        paymentIntentRepository.save(newIntent(
                "pi_newer",
                merchantId,
                CREATED_AT.plusSeconds(1)
        ));
        paymentIntentRepository.save(newIntent(
                "pi_other_merchant",
                otherMerchantId,
                CREATED_AT.plusSeconds(2)
        ));

        assertThat(paymentIntentRepository.searchByMerchant(merchantId))
                .extracting(PaymentIntent::publicId)
                .containsExactly("pi_newer", "pi_older");
        assertThat(paymentIntentRepository.searchByMerchant(otherMerchantId))
                .extracting(PaymentIntent::publicId)
                .containsExactly("pi_other_merchant");
    }

    @Test
    void shouldPersistAndReloadPaymentTransactionWithTerminalMetadataAndTimestamps() {
        long merchantId = insertMerchant("mrc_transaction_owner");
        PaymentIntent intent = paymentIntentRepository.save(newIntent(
                "pi_transaction_parent",
                merchantId,
                CREATED_AT
        ));
        Instant startedAt = CREATED_AT.plusSeconds(10);
        PaymentTransaction processing = paymentTransactionRepository.save(
                PaymentTransaction.createProcessing(
                        "ptxn_persistence",
                        intent.internalId(),
                        1,
                        "SIMULATOR",
                        startedAt
                )
        );

        processing.markUnknown(
                null,
                "PROVIDER_TIMEOUT",
                "The provider outcome is unknown.",
                startedAt.plusSeconds(3)
        );
        PaymentTransaction updated = paymentTransactionRepository.save(processing);
        PaymentTransaction reloaded = paymentTransactionRepository
                .findLatestByPaymentIntentId(intent.internalId())
                .orElseThrow();

        assertThat(processing.internalId()).isPositive();
        assertThat(processing.version()).isZero();
        assertThat(updated.version()).isEqualTo(1L);
        assertThat(reloaded.publicId()).isEqualTo("ptxn_persistence");
        assertThat(reloaded.paymentIntentId()).isEqualTo(intent.internalId());
        assertThat(reloaded.attemptNo()).isEqualTo(1);
        assertThat(reloaded.provider()).isEqualTo("SIMULATOR");
        assertThat(reloaded.status()).isEqualTo(PaymentTransactionStatus.UNKNOWN);
        assertThat(reloaded.failureCode()).isEqualTo("PROVIDER_TIMEOUT");
        assertThat(reloaded.failureMessage()).isEqualTo("The provider outcome is unknown.");
        assertThat(reloaded.startedAt()).isEqualTo(startedAt);
        assertThat(reloaded.completedAt()).isEqualTo(startedAt.plusSeconds(3));
        assertThat(storedTimestampEquals(
                "created_at",
                processing.internalId(),
                startedAt
        )).isTrue();
        assertThat(storedTimestampEquals(
                "updated_at",
                processing.internalId(),
                startedAt.plusSeconds(3)
        )).isTrue();
    }

    @Test
    void shouldOrderTransactionsByAttemptAndReturnLatestAttempt() {
        long merchantId = insertMerchant("mrc_transaction_order");
        PaymentIntent intent = paymentIntentRepository.save(newIntent(
                "pi_transaction_order",
                merchantId,
                CREATED_AT
        ));
        paymentTransactionRepository.save(PaymentTransaction.createProcessing(
                "ptxn_attempt_1",
                intent.internalId(),
                1,
                "SIMULATOR",
                CREATED_AT.plusSeconds(1)
        ));
        paymentTransactionRepository.save(PaymentTransaction.createProcessing(
                "ptxn_attempt_2",
                intent.internalId(),
                2,
                "SIMULATOR",
                CREATED_AT.plusSeconds(2)
        ));

        assertThat(paymentTransactionRepository.findByPaymentIntentId(intent.internalId()))
                .extracting(PaymentTransaction::attemptNo)
                .containsExactly(1, 2);
        assertThat(paymentTransactionRepository.findLatestByPaymentIntentId(intent.internalId()))
                .get()
                .extracting(PaymentTransaction::publicId)
                .isEqualTo("ptxn_attempt_2");
    }

    @Test
    void shouldRejectStalePaymentIntentVersion() {
        long merchantId = insertMerchant("mrc_optimistic_lock");
        PaymentIntent saved = paymentIntentRepository.save(newIntent(
                "pi_optimistic_lock",
                merchantId,
                CREATED_AT
        ));
        PaymentIntent firstSnapshot = paymentIntentRepository
                .findByPublicIdAndMerchantId(saved.publicId(), merchantId)
                .orElseThrow();
        PaymentIntent staleSnapshot = paymentIntentRepository
                .findByPublicIdAndMerchantId(saved.publicId(), merchantId)
                .orElseThrow();
        firstSnapshot.startProcessing(CREATED_AT.plusSeconds(1));
        staleSnapshot.startProcessing(CREATED_AT.plusSeconds(2));

        PaymentIntent updated = paymentIntentRepository.save(firstSnapshot);

        assertThat(updated.version()).isEqualTo(1L);
        assertThatThrownBy(() -> paymentIntentRepository.save(staleSnapshot))
                .isInstanceOf(OptimisticLockingFailureException.class);
    }

    @Test
    void repositoryPortsShouldNotLeakPersistenceTypes() {
        assertPortDoesNotLeakPersistenceTypes(PaymentIntentRepository.class);
        assertPortDoesNotLeakPersistenceTypes(PaymentTransactionRepository.class);
        assertThat(Modifier.isPublic(PaymentIntentEntity.class.getModifiers())).isFalse();
        assertThat(Modifier.isPublic(PaymentTransactionEntity.class.getModifiers())).isFalse();
        assertThat(Modifier.isPublic(PaymentIntentJpaRepository.class.getModifiers())).isFalse();
        assertThat(Modifier.isPublic(PaymentTransactionJpaRepository.class.getModifiers())).isFalse();
    }

    private PaymentIntent newIntent(String publicId, long merchantId, Instant createdAt) {
        return PaymentIntent.create(
                publicId,
                merchantId,
                null,
                null,
                Money.of(1_000L, "USD"),
                createdAt
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
                "Payment Store",
                CREATED_AT.atOffset(ZoneOffset.UTC),
                CREATED_AT.atOffset(ZoneOffset.UTC)
        );
        return id;
    }

    private boolean storedTimestampEquals(String column, long transactionId, Instant expected) {
        Boolean matches = jdbcTemplate.queryForObject(
                "SELECT " + column + " = ? FROM payment_transactions WHERE id = ?",
                Boolean.class,
                expected.atOffset(ZoneOffset.UTC),
                transactionId
        );
        return Boolean.TRUE.equals(matches);
    }

    private static void assertPortDoesNotLeakPersistenceTypes(Class<?> repositoryPort) {
        assertThat(Arrays.stream(repositoryPort.getDeclaredMethods())
                .map(Method::toGenericString))
                .noneMatch(signature -> signature.contains(".infrastructure.persistence."))
                .noneMatch(signature -> signature.contains("org.springframework.data."))
                .noneMatch(signature -> signature.contains("jakarta.persistence."));
    }
}
