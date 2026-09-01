package com.flowpay.backend.refund.infrastructure.persistence;

import com.flowpay.backend.common.money.Money;
import com.flowpay.backend.refund.application.RefundPage;
import com.flowpay.backend.refund.application.RefundRepository;
import com.flowpay.backend.refund.domain.Refund;
import com.flowpay.backend.refund.domain.RefundFailure;
import com.flowpay.backend.refund.domain.RefundReason;
import com.flowpay.backend.refund.domain.RefundStatus;
import com.flowpay.backend.testing.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class RefundPersistenceTest extends PostgresIntegrationTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-31T09:00:00Z");
    private static final Money AMOUNT = Money.of(12_345L, "USD");

    @Autowired
    private RefundRepository refundRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void cleanRefundData() {
        jdbcTemplate.update("""
                TRUNCATE TABLE refunds, payment_transactions, payment_intents, merchants
                RESTART IDENTITY CASCADE
                """);
    }

    @Test
    void shouldPersistAndReloadProcessingRefundWithExactMoneyAndOwnership() {
        RefundOwner owner = insertOwner("processing");
        Refund saved = refundRepository.save(processingRefund(
                "re_persistence",
                owner,
                AMOUNT,
                CREATED_AT
        ));
        Refund reloaded = refundRepository.findByPublicIdAndMerchantId(
                saved.publicId(),
                owner.merchantId()
        ).orElseThrow();

        assertThat(saved.internalId()).isPositive();
        assertThat(saved.version()).isZero();
        assertThat(reloaded.internalId()).isEqualTo(saved.internalId());
        assertThat(reloaded.publicId()).isEqualTo("re_persistence");
        assertThat(reloaded.merchantId()).isEqualTo(owner.merchantId());
        assertThat(reloaded.paymentIntentId()).isEqualTo(owner.paymentIntentId());
        assertThat(reloaded.amount()).isEqualTo(Money.of(12_345L, "USD"));
        assertThat(reloaded.reason().value()).isEqualTo("CUSTOMER_REQUEST");
        assertThat(reloaded.provider()).isEqualTo("SIMULATOR");
        assertThat(reloaded.status()).isEqualTo(RefundStatus.PROCESSING);
        assertThat(reloaded.providerRefundId()).isNull();
        assertThat(reloaded.failure()).isNull();
        assertThat(reloaded.createdAt()).isEqualTo(CREATED_AT);
        assertThat(reloaded.updatedAt()).isEqualTo(CREATED_AT.plusSeconds(1));
        assertThat(reloaded.completedAt()).isNull();
        assertThat(storedMoney(saved.internalId())).isEqualTo("12345:USD");
    }

    @Test
    void shouldPersistAndReloadSucceededRefund() {
        RefundOwner owner = insertOwner("succeeded");
        Refund processing = refundRepository.save(processingRefund(
                "re_succeeded",
                owner,
                AMOUNT,
                CREATED_AT
        ));

        processing.markSucceeded(" provider_re_123 ", CREATED_AT.plusSeconds(2));
        Refund saved = refundRepository.save(processing);
        Refund reloaded = refundRepository.findByPublicIdAndMerchantId(
                saved.publicId(),
                owner.merchantId()
        ).orElseThrow();

        assertThat(saved.version()).isEqualTo(1L);
        assertThat(reloaded.status()).isEqualTo(RefundStatus.SUCCEEDED);
        assertThat(reloaded.providerRefundId()).isEqualTo("provider_re_123");
        assertThat(reloaded.failure()).isNull();
        assertThat(reloaded.completedAt()).isEqualTo(CREATED_AT.plusSeconds(2));
    }

    @Test
    void shouldPersistAndReloadFailedRefundWithSafeMetadata() {
        RefundOwner owner = insertOwner("failed");
        Refund processing = refundRepository.save(processingRefund(
                "re_failed",
                owner,
                AMOUNT,
                CREATED_AT
        ));

        processing.markFailed(
                null,
                RefundFailure.of("DECLINED", "The provider declined the refund."),
                CREATED_AT.plusSeconds(2)
        );
        Refund saved = refundRepository.save(processing);
        Refund reloaded = refundRepository.findByPublicIdAndMerchantId(
                saved.publicId(),
                owner.merchantId()
        ).orElseThrow();

        assertThat(reloaded.status()).isEqualTo(RefundStatus.FAILED);
        assertThat(reloaded.providerRefundId()).isNull();
        assertThat(reloaded.failureCode()).isEqualTo("DECLINED");
        assertThat(reloaded.failureMessage()).isEqualTo(
                "The provider declined the refund."
        );
        assertThat(reloaded.completedAt()).isEqualTo(CREATED_AT.plusSeconds(2));
    }

    @Test
    void shouldScopeFindAndPaginatedPaymentListByMerchant() {
        RefundOwner owner = insertOwner("list");
        RefundOwner otherOwner = insertOwner("list_other");
        refundRepository.save(processingRefund(
                "re_oldest", owner, AMOUNT, CREATED_AT
        ));
        refundRepository.save(processingRefund(
                "re_middle", owner, AMOUNT, CREATED_AT.plusSeconds(10)
        ));
        refundRepository.save(processingRefund(
                "re_newest", owner, AMOUNT, CREATED_AT.plusSeconds(20)
        ));
        refundRepository.save(processingRefund(
                "re_other", otherOwner, AMOUNT, CREATED_AT.plusSeconds(30)
        ));

        assertThat(refundRepository.findByPublicIdAndMerchantId(
                "re_newest",
                otherOwner.merchantId()
        )).isEmpty();
        Boolean lockedCrossMerchantIsEmpty = new TransactionTemplate(transactionManager).execute(
                status -> refundRepository.findByPublicIdAndMerchantIdForUpdate(
                        "re_newest",
                        otherOwner.merchantId()
                ).isEmpty()
        );
        assertThat(lockedCrossMerchantIsEmpty).isTrue();
        assertThat(refundRepository.findByPaymentIntentIdAndMerchantId(
                owner.paymentIntentId(),
                otherOwner.merchantId(),
                0,
                20
        ).content()).isEmpty();

        RefundPage firstPage = refundRepository.findByPaymentIntentIdAndMerchantId(
                owner.paymentIntentId(),
                owner.merchantId(),
                0,
                2
        );
        RefundPage secondPage = refundRepository.findByPaymentIntentIdAndMerchantId(
                owner.paymentIntentId(),
                owner.merchantId(),
                1,
                2
        );

        assertThat(firstPage.content())
                .extracting(Refund::publicId)
                .containsExactly("re_newest", "re_middle");
        assertThat(firstPage.page()).isZero();
        assertThat(firstPage.size()).isEqualTo(2);
        assertThat(firstPage.totalElements()).isEqualTo(3L);
        assertThat(firstPage.totalPages()).isEqualTo(2);
        assertThat(firstPage.hasNext()).isTrue();
        assertThat(firstPage.hasPrevious()).isFalse();
        assertThat(secondPage.content())
                .extracting(Refund::publicId)
                .containsExactly("re_oldest");
        assertThat(secondPage.hasNext()).isFalse();
        assertThat(secondPage.hasPrevious()).isTrue();
    }

    @Test
    void shouldRejectStaleRefundVersion() {
        RefundOwner owner = insertOwner("optimistic_lock");
        Refund saved = refundRepository.save(processingRefund(
                "re_optimistic_lock",
                owner,
                AMOUNT,
                CREATED_AT
        ));
        Refund firstSnapshot = refundRepository.findByPublicIdAndMerchantId(
                saved.publicId(),
                owner.merchantId()
        ).orElseThrow();
        Refund staleSnapshot = refundRepository.findByPublicIdAndMerchantId(
                saved.publicId(),
                owner.merchantId()
        ).orElseThrow();
        firstSnapshot.markSucceeded("provider_re_success", CREATED_AT.plusSeconds(2));
        staleSnapshot.markFailed(
                null,
                RefundFailure.of("DECLINED", "Declined"),
                CREATED_AT.plusSeconds(3)
        );

        Refund updated = refundRepository.save(firstSnapshot);

        assertThat(updated.version()).isEqualTo(1L);
        assertThatThrownBy(() -> refundRepository.save(staleSnapshot))
                .isInstanceOf(OptimisticLockingFailureException.class);
    }

    @Test
    void lockedLookupShouldSerializeFinalizersAndReloadTerminalState() throws Exception {
        RefundOwner owner = insertOwner("pessimistic_lock");
        Refund saved = refundRepository.save(processingRefund(
                "re_pessimistic_lock",
                owner,
                AMOUNT,
                CREATED_AT
        ));
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        CountDownLatch firstLocked = new CountDownLatch(1);
        CountDownLatch allowFirstCommit = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<RefundStatus> first = executor.submit(() -> transaction.execute(status -> {
                Refund locked = lockedRefund(saved, owner);
                firstLocked.countDown();
                await(allowFirstCommit);
                locked.markSucceeded("provider_re_locked", CREATED_AT.plusSeconds(2));
                return refundRepository.save(locked).status();
            }));
            assertThat(firstLocked.await(10, TimeUnit.SECONDS)).isTrue();

            Future<RefundStatus> second = executor.submit(() ->
                transaction.execute(status -> {
                    secondStarted.countDown();
                    Refund locked = lockedRefund(saved, owner);
                    assertInvalidSecondFinalization(locked);
                    return locked.status();
                })
            );
            assertThat(secondStarted.await(10, TimeUnit.SECONDS)).isTrue();
            try {
                assertThatThrownBy(() -> second.get(300, TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
            } finally {
                allowFirstCommit.countDown();
            }

            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(RefundStatus.SUCCEEDED);
            assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo(RefundStatus.SUCCEEDED);
            assertThat(refundRepository.findByPublicIdAndMerchantId(
                    saved.publicId(),
                    owner.merchantId()
            )).get().extracting(Refund::status).isEqualTo(RefundStatus.SUCCEEDED);
        } finally {
            allowFirstCommit.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void persistenceContractShouldNotLeakJpaOrCrossModuleAssociations() {
        assertPortDoesNotLeakPersistenceTypes(RefundRepository.class);
        assertThat(Modifier.isPublic(RefundEntity.class.getModifiers())).isFalse();
        assertThat(Modifier.isPublic(RefundJpaRepository.class.getModifiers())).isFalse();
        assertThat(Modifier.isPublic(RefundPersistenceMapper.class.getModifiers())).isFalse();
        assertThat(Arrays.stream(RefundPersistenceMapper.class.getDeclaredMethods()))
                .noneMatch(method -> Modifier.isPublic(method.getModifiers()));
        assertThat(Arrays.stream(RefundEntity.class.getDeclaredFields())
                .map(field -> field.getType().getName()))
                .noneMatch(type -> type.contains(".identity."))
                .noneMatch(type -> type.contains(".merchant."))
                .noneMatch(type -> type.contains(".payment."));
        assertThat(Arrays.stream(RefundEntity.class.getDeclaredFields())
                .flatMap(field -> Arrays.stream(field.getAnnotations()))
                .map(annotation -> annotation.annotationType().getSimpleName()))
                .noneMatch(name -> name.equals("ManyToOne"))
                .noneMatch(name -> name.equals("OneToOne"))
                .noneMatch(name -> name.equals("OneToMany"));
    }

    private Refund lockedRefund(Refund saved, RefundOwner owner) {
        return refundRepository.findByPublicIdAndMerchantIdForUpdate(
                saved.publicId(),
                owner.merchantId()
        ).orElseThrow();
    }

    private static void assertInvalidSecondFinalization(Refund locked) {
        assertThatThrownBy(() -> locked.markFailed(
                null,
                RefundFailure.of("DECLINED", "Late finalizer"),
                CREATED_AT.plusSeconds(3)
        )).isInstanceOf(IllegalStateException.class)
                .hasMessage("Refund cannot transition from SUCCEEDED to FAILED");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for concurrency test latch");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for concurrency test latch", exception);
        }
    }

    private Refund processingRefund(
            String publicId,
            RefundOwner owner,
            Money amount,
            Instant createdAt
    ) {
        Refund refund = Refund.create(
                publicId,
                owner.merchantId(),
                owner.paymentIntentId(),
                amount,
                RefundReason.of(" CUSTOMER_REQUEST "),
                " SIMULATOR ",
                createdAt
        );
        refund.startProcessing(createdAt.plusSeconds(1));
        return refund;
    }

    private RefundOwner insertOwner(String suffix) {
        Long merchantId = jdbcTemplate.queryForObject(
                """
                INSERT INTO merchants (public_id, name, status, created_at, updated_at, version)
                VALUES (?, 'Refund Store', 'ACTIVE', ?, ?, 0)
                RETURNING id
                """,
                Long.class,
                "mrc_refund_" + suffix,
                CREATED_AT.atOffset(ZoneOffset.UTC),
                CREATED_AT.atOffset(ZoneOffset.UTC)
        );
        Long paymentIntentId = jdbcTemplate.queryForObject(
                """
                INSERT INTO payment_intents (
                    public_id, merchant_id, amount_minor, currency, status,
                    created_at, updated_at, version
                )
                VALUES (?, ?, 1000000, 'USD', 'SUCCEEDED', ?, ?, 0)
                RETURNING id
                """,
                Long.class,
                "pi_refund_" + suffix,
                merchantId,
                CREATED_AT.atOffset(ZoneOffset.UTC),
                CREATED_AT.atOffset(ZoneOffset.UTC)
        );
        return new RefundOwner(merchantId, paymentIntentId);
    }

    private String storedMoney(long refundId) {
        return jdbcTemplate.queryForObject(
                "SELECT amount_minor || ':' || TRIM(currency) FROM refunds WHERE id = ?",
                String.class,
                refundId
        );
    }

    private static void assertPortDoesNotLeakPersistenceTypes(Class<?> repositoryPort) {
        assertThat(Arrays.stream(repositoryPort.getDeclaredMethods())
                .map(Method::toGenericString))
                .noneMatch(signature -> signature.contains(".infrastructure.persistence."))
                .noneMatch(signature -> signature.contains("org.springframework.data."))
                .noneMatch(signature -> signature.contains("jakarta.persistence."));
    }

    private record RefundOwner(long merchantId, long paymentIntentId) {
    }
}
