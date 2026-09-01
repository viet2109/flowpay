package com.flowpay.backend.refund.api;

import com.flowpay.backend.merchant.application.ApiKeyRepository;
import com.flowpay.backend.merchant.application.ApiKeySecretCodec;
import com.flowpay.backend.merchant.application.GeneratedApiKeySecret;
import com.flowpay.backend.merchant.application.MerchantRepository;
import com.flowpay.backend.merchant.domain.ApiKey;
import com.flowpay.backend.merchant.domain.Merchant;
import com.flowpay.backend.refund.application.RefundProviderPort;
import com.flowpay.backend.refund.application.RefundProviderRequest;
import com.flowpay.backend.refund.application.RefundProviderResult;
import com.flowpay.backend.refund.domain.RefundProviderOutcome;
import com.flowpay.backend.testing.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PhaseFourEndToEndTest.ProviderConfiguration.class)
class PhaseFourEndToEndTest extends PostgresIntegrationTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-31T08:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MerchantRepository merchantRepository;

    @Autowired
    private ApiKeyRepository apiKeyRepository;

    @Autowired
    private ApiKeySecretCodec secretCodec;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ControllableRefundProvider provider;

    @BeforeEach
    void cleanData() {
        provider.reset();
        jdbcTemplate.update("""
                TRUNCATE TABLE refunds, idempotency_records, payment_transactions,
                    payment_intents, refresh_tokens, merchant_api_keys, merchant_members,
                    merchants, users RESTART IDENTITY CASCADE
                """);
    }

    @Test
    void concurrentSameRequestShouldHaveOneOwnerAndOneProviderInvocation() throws Exception {
        StoredKey stored = createStoredKey("same_request");
        PaymentFixture payment = insertRefundablePayment(stored, "same_request", 100L);
        String idempotencyKey = "refund-concurrent-same";
        String body = requestBody(60L, "CUSTOMER_REQUEST");
        provider.blockNextInvocation();
        ExecutorService executor = Executors.newSingleThreadExecutor();

        MvcResult original;
        try {
            Future<MvcResult> owner = executor.submit(() -> performCreate(
                    stored,
                    payment.publicId(),
                    idempotencyKey,
                    body
            ).andReturn());
            assertThat(provider.awaitInvocation()).isTrue();
            assertCapacity(payment, 0L, 60L, "SUCCEEDED");

            performCreate(stored, payment.publicId(), idempotencyKey, body)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code")
                            .value("IDEMPOTENCY_REQUEST_IN_PROGRESS"));

            provider.releaseInvocation();
            original = owner.get(30, TimeUnit.SECONDS);
            assertThat(original.getResponse().getStatus()).isEqualTo(201);
        } finally {
            provider.releaseInvocation();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        MvcResult replay = performCreate(stored, payment.publicId(), idempotencyKey, body)
                .andExpect(status().isCreated())
                .andExpect(header().string(RefundIdempotencyHeaders.REPLAYED, "true"))
                .andReturn();

        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(original.getResponse().getContentAsString());
        assertThat(replay.getResponse().getHeader(HttpHeaders.LOCATION))
                .isEqualTo(original.getResponse().getHeader(HttpHeaders.LOCATION));
        assertThat(provider.invocationCount()).isEqualTo(1);
        assertThat(countRefunds(stored.merchantId())).isEqualTo(1);
        assertThat(countIdempotency(stored.merchantId())).isEqualTo(1);
        assertCapacity(payment, 60L, 0L, "PARTIALLY_REFUNDED");
    }

    @Test
    void sameKeyWithDifferentRequestShouldNotRepeatProviderOrFinancialEffects()
            throws Exception {
        StoredKey stored = createStoredKey("reused");
        PaymentFixture payment = insertRefundablePayment(stored, "reused", 100L);
        String idempotencyKey = "refund-reused-request";

        performCreate(
                stored,
                payment.publicId(),
                idempotencyKey,
                requestBody(30L, "CUSTOMER_REQUEST")
        ).andExpect(status().isCreated());
        Map<String, Object> beforeDuplicate = capacity(payment);

        performCreate(
                stored,
                payment.publicId(),
                idempotencyKey,
                requestBody(31L, "CUSTOMER_REQUEST")
        )
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        assertThat(provider.invocationCount()).isEqualTo(1);
        assertThat(capacity(payment)).isEqualTo(beforeDuplicate);
        assertThat(countRefunds(stored.merchantId())).isEqualTo(1);
        assertThat(countIdempotency(stored.merchantId())).isEqualTo(1);
        assertCapacity(payment, 30L, 0L, "PARTIALLY_REFUNDED");
    }

    @Test
    void concurrentDifferentKeysShouldPreventOverRefund() throws Exception {
        StoredKey stored = createStoredKey("over_refund");
        PaymentFixture payment = insertRefundablePayment(stored, "over_refund", 100L);

        List<MvcResult> results = createConcurrently(
                stored,
                payment,
                new Attempt("refund-over-a", 80L),
                new Attempt("refund-over-b", 80L)
        );

        assertOneCreatedAndOneCapacityConflict(results);
        assertThat(provider.invocationCount()).isEqualTo(1);
        assertThat(countRefunds(stored.merchantId())).isEqualTo(1);
        assertThat(countIdempotency(stored.merchantId())).isEqualTo(1);
        assertCapacity(payment, 80L, 0L, "PARTIALLY_REFUNDED");
    }

    @Test
    void concurrentDifferentKeysWhoseAmountsFitShouldBothSucceed() throws Exception {
        StoredKey stored = createStoredKey("both_fit");
        PaymentFixture payment = insertRefundablePayment(stored, "both_fit", 100L);

        List<MvcResult> results = createConcurrently(
                stored,
                payment,
                new Attempt("refund-fit-a", 30L),
                new Attempt("refund-fit-b", 40L)
        );

        assertThat(results).allSatisfy(result ->
                assertThat(result.getResponse().getStatus()).isEqualTo(201)
        );
        assertThat(provider.invocationCount()).isEqualTo(2);
        assertThat(countRefunds(stored.merchantId())).isEqualTo(2);
        assertThat(countIdempotency(stored.merchantId())).isEqualTo(2);
        assertCapacity(payment, 70L, 0L, "PARTIALLY_REFUNDED");
    }

    @Test
    void concurrentFullAndPartialRefundsShouldHaveOnlyOneCapacityOwner()
            throws Exception {
        StoredKey stored = createStoredKey("full_race");
        PaymentFixture payment = insertRefundablePayment(stored, "full_race", 100L);

        List<MvcResult> results = createConcurrently(
                stored,
                payment,
                new Attempt("refund-full", 100L),
                new Attempt("refund-one", 1L)
        );

        assertOneCreatedAndOneCapacityConflict(results);
        Map<String, Object> finalCapacity = capacity(payment);
        long refunded = longValue(finalCapacity, "refunded_amount_minor");
        assertThat(refunded).isIn(1L, 100L);
        assertThat(longValue(finalCapacity, "refund_reserved_minor")).isZero();
        assertThat(finalCapacity.get("status")).isIn("PARTIALLY_REFUNDED", "REFUNDED");
        assertInvariant(finalCapacity);
        assertThat(provider.invocationCount()).isEqualTo(1);
        assertThat(countRefunds(stored.merchantId())).isEqualTo(1);
        assertThat(countIdempotency(stored.merchantId())).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(
            value = RefundProviderOutcome.class,
            names = {"DECLINED", "TECHNICAL_FAILURE"}
    )
    void knownFailureShouldReleaseCapacityForLaterRefund(
            RefundProviderOutcome outcome
    ) throws Exception {
        StoredKey stored = createStoredKey("released_" + outcome.name().toLowerCase());
        PaymentFixture payment = insertRefundablePayment(
                stored,
                "released_" + outcome.name().toLowerCase(),
                100L
        );
        provider.respondWith(outcome);

        performCreate(
                stored,
                payment.publicId(),
                "refund-known-failure",
                requestBody(60L, null)
        )
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("FAILED"));
        assertCapacity(payment, 0L, 0L, "SUCCEEDED");

        provider.respondWith(RefundProviderOutcome.SUCCESS);
        performCreate(
                stored,
                payment.publicId(),
                "refund-after-release",
                requestBody(100L, null)
        )
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"));

        assertCapacity(payment, 100L, 0L, "REFUNDED");
        assertThat(provider.invocationCount()).isEqualTo(2);
        assertThat(countRefunds(stored.merchantId())).isEqualTo(2);
        assertThat(countIdempotency(stored.merchantId())).isEqualTo(2);
    }

    @Test
    void unknownOutcomeShouldRetainCapacityAndConstrainLaterRefunds() throws Exception {
        StoredKey stored = createStoredKey("unknown");
        PaymentFixture payment = insertRefundablePayment(stored, "unknown", 100L);
        provider.respondWith(RefundProviderOutcome.UNKNOWN);

        performCreate(
                stored,
                payment.publicId(),
                "refund-unknown",
                requestBody(60L, null)
        )
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("PROCESSING"));
        assertCapacity(payment, 0L, 60L, "SUCCEEDED");

        provider.respondWith(RefundProviderOutcome.SUCCESS);
        performCreate(
                stored,
                payment.publicId(),
                "refund-too-large-after-unknown",
                requestBody(41L, null)
        )
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code")
                        .value("REFUND_AMOUNT_EXCEEDS_AVAILABLE"));
        assertThat(provider.invocationCount()).isEqualTo(1);
        assertCapacity(payment, 0L, 60L, "SUCCEEDED");

        performCreate(
                stored,
                payment.publicId(),
                "refund-remaining-capacity",
                requestBody(40L, null)
        )
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"));

        assertCapacity(payment, 40L, 60L, "PARTIALLY_REFUNDED");
        assertThat(provider.invocationCount()).isEqualTo(2);
        assertThat(countRefunds(stored.merchantId())).isEqualTo(2);
        assertThat(countIdempotency(stored.merchantId())).isEqualTo(2);
    }

    @ParameterizedTest
    @EnumSource(RefundProviderOutcome.class)
    void completedReplayShouldPreserveStatusBodyLocationAndCapacity(
            RefundProviderOutcome outcome
    ) throws Exception {
        String suffix = "replay_" + outcome.name().toLowerCase();
        StoredKey stored = createStoredKey(suffix);
        PaymentFixture payment = insertRefundablePayment(stored, suffix, 100L);
        provider.respondWith(outcome);
        String idempotencyKey = "refund-" + suffix;
        String body = requestBody(60L, "CUSTOMER_REQUEST");
        int expectedStatus = outcome == RefundProviderOutcome.UNKNOWN ? 202 : 201;

        MvcResult original = performCreate(
                stored,
                payment.publicId(),
                idempotencyKey,
                body
        )
                .andExpect(status().is(expectedStatus))
                .andExpect(header().doesNotExist(RefundIdempotencyHeaders.REPLAYED))
                .andReturn();
        Map<String, Object> capacityBeforeReplay = capacity(payment);

        MvcResult replay = performCreate(
                stored,
                payment.publicId(),
                idempotencyKey,
                body
        )
                .andExpect(status().is(expectedStatus))
                .andExpect(header().string(RefundIdempotencyHeaders.REPLAYED, "true"))
                .andReturn();

        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(original.getResponse().getContentAsString());
        assertThat(replay.getResponse().getHeader(HttpHeaders.LOCATION))
                .isEqualTo(original.getResponse().getHeader(HttpHeaders.LOCATION));
        assertThat(capacity(payment)).isEqualTo(capacityBeforeReplay);
        assertInvariant(capacityBeforeReplay);
        assertThat(provider.invocationCount()).isEqualTo(1);
        assertThat(countRefunds(stored.merchantId())).isEqualTo(1);
        assertThat(countIdempotency(stored.merchantId())).isEqualTo(1);
    }

    @Test
    void crossMerchantPaymentGuessShouldNotCreateIdempotencyOrFinancialState()
            throws Exception {
        StoredKey owner = createStoredKey("owner");
        StoredKey other = createStoredKey("other");
        PaymentFixture payment = insertRefundablePayment(owner, "owned", 100L);

        performCreate(
                other,
                payment.publicId(),
                "refund-cross-merchant",
                requestBody(50L, null)
        )
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYMENT_NOT_FOUND"));

        assertThat(provider.invocationCount()).isZero();
        assertThat(countRefunds(owner.merchantId())).isZero();
        assertThat(countRefunds(other.merchantId())).isZero();
        assertThat(countIdempotency(other.merchantId())).isZero();
        assertCapacity(payment, 0L, 0L, "SUCCEEDED");
    }

    @Test
    void cleanPostgreSqlShouldContainTheCompletePhaseFourMigrationChain() {
        assertThat(jdbcTemplate.queryForList("""
                SELECT version::integer
                FROM flyway_schema_history
                WHERE success = true
                ORDER BY installed_rank
                """, Integer.class)).containsExactly(1, 2, 3, 4, 5, 6, 7);
        assertThat(jdbcTemplate.queryForObject("SELECT version()", String.class))
                .contains("PostgreSQL");
    }

    private List<MvcResult> createConcurrently(
            StoredKey stored,
            PaymentFixture payment,
            Attempt firstAttempt,
            Attempt secondAttempt
    ) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<MvcResult> first = executor.submit(() -> concurrentCreate(
                    stored,
                    payment,
                    firstAttempt,
                    ready,
                    start
            ));
            Future<MvcResult> second = executor.submit(() -> concurrentCreate(
                    stored,
                    payment,
                    secondAttempt,
                    ready,
                    start
            ));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(
                    first.get(30, TimeUnit.SECONDS),
                    second.get(30, TimeUnit.SECONDS)
            );
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private MvcResult concurrentCreate(
            StoredKey stored,
            PaymentFixture payment,
            Attempt attempt,
            CountDownLatch ready,
            CountDownLatch start
    ) throws Exception {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Concurrent Refund request did not start in time");
        }
        return performCreate(
                stored,
                payment.publicId(),
                attempt.idempotencyKey(),
                requestBody(attempt.amount(), "CUSTOMER_REQUEST")
        ).andReturn();
    }

    private void assertOneCreatedAndOneCapacityConflict(List<MvcResult> results)
            throws Exception {
        assertThat(results).filteredOn(result -> result.getResponse().getStatus() == 201)
                .hasSize(1);
        MvcResult conflict = results.stream()
                .filter(result -> result.getResponse().getStatus() == 409)
                .findFirst()
                .orElseThrow();
        assertThat(objectMapper.readTree(conflict.getResponse().getContentAsByteArray())
                .path("code")
                .stringValue()).isEqualTo("REFUND_AMOUNT_EXCEEDS_AVAILABLE");
    }

    private org.springframework.test.web.servlet.ResultActions performCreate(
            StoredKey stored,
            String paymentPublicId,
            String idempotencyKey,
            String body
    ) throws Exception {
        return mockMvc.perform(post(path(paymentPublicId))
                .header(HttpHeaders.AUTHORIZATION, bearer(stored.rawKey()))
                .header(RefundIdempotencyHeaders.KEY, idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private StoredKey createStoredKey(String suffix) {
        Merchant merchant = merchantRepository.save(Merchant.create(
                "mrc_phase_four_" + suffix,
                "Phase Four Store",
                CREATED_AT
        ));
        GeneratedApiKeySecret secret = secretCodec.generate();
        apiKeyRepository.save(ApiKey.create(
                "key_phase_four_" + suffix,
                merchant.id(),
                "Phase Four key",
                secret.prefix(),
                secret.digest(),
                null,
                CREATED_AT
        ));
        return new StoredKey(merchant.id(), secret.rawKey());
    }

    private PaymentFixture insertRefundablePayment(
            StoredKey stored,
            String suffix,
            long amount
    ) {
        String paymentPublicId = "pi_phase_four_" + suffix;
        long paymentInternalId = jdbcTemplate.queryForObject(
                """
                INSERT INTO payment_intents (
                    public_id, merchant_id, amount_minor, currency, status,
                    refunded_amount_minor, refund_reserved_minor,
                    created_at, updated_at, version
                )
                VALUES (?, ?, ?, 'USD', 'SUCCEEDED', 0, 0, ?, ?, 0)
                RETURNING id
                """,
                Long.class,
                paymentPublicId,
                stored.merchantId(),
                amount,
                utc(CREATED_AT),
                utc(CREATED_AT.plusSeconds(1))
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
                "ptxn_phase_four_" + suffix,
                paymentInternalId,
                "provider_charge_phase_four_" + suffix,
                utc(CREATED_AT),
                utc(CREATED_AT.plusSeconds(1)),
                utc(CREATED_AT),
                utc(CREATED_AT.plusSeconds(1))
        );
        return new PaymentFixture(paymentInternalId, paymentPublicId);
    }

    private Map<String, Object> capacity(PaymentFixture payment) {
        Map<String, Object> capacity = jdbcTemplate.queryForMap(
                """
                SELECT status, amount_minor, refunded_amount_minor, refund_reserved_minor
                FROM payment_intents
                WHERE id = ?
                """,
                payment.internalId()
        );
        assertInvariant(capacity);
        return capacity;
    }

    private void assertCapacity(
            PaymentFixture payment,
            long expectedRefunded,
            long expectedReserved,
            String expectedStatus
    ) {
        Map<String, Object> capacity = capacity(payment);
        assertThat(capacity.get("status")).isEqualTo(expectedStatus);
        assertThat(longValue(capacity, "refunded_amount_minor"))
                .isEqualTo(expectedRefunded);
        assertThat(longValue(capacity, "refund_reserved_minor"))
                .isEqualTo(expectedReserved);
    }

    private static void assertInvariant(Map<String, Object> capacity) {
        long amount = longValue(capacity, "amount_minor");
        long refunded = longValue(capacity, "refunded_amount_minor");
        long reserved = longValue(capacity, "refund_reserved_minor");
        assertThat(refunded).isNotNegative();
        assertThat(reserved).isNotNegative();
        assertThat(refunded + reserved).isLessThanOrEqualTo(amount);
    }

    private int countRefunds(long merchantId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM refunds WHERE merchant_id = ?",
                Integer.class,
                merchantId
        );
    }

    private int countIdempotency(long merchantId) {
        return jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM idempotency_records
                WHERE merchant_id = ?
                  AND operation = 'REFUND_CREATE'
                """,
                Integer.class,
                merchantId
        );
    }

    private static long longValue(Map<String, Object> values, String key) {
        return ((Number) values.get(key)).longValue();
    }

    private static String requestBody(long amount, String reason) {
        String reasonJson = reason == null ? "null" : "\"" + reason + "\"";
        return """
                {
                  "amount": %d,
                  "reason": %s
                }
                """.formatted(amount, reasonJson);
    }

    private static String path(String paymentPublicId) {
        return "/api/v1/payment-intents/" + paymentPublicId + "/refunds";
    }

    private static String bearer(String rawKey) {
        return "Bearer " + rawKey;
    }

    private static java.time.OffsetDateTime utc(Instant value) {
        return value.atOffset(ZoneOffset.UTC);
    }

    private record StoredKey(long merchantId, String rawKey) {
    }

    private record PaymentFixture(long internalId, String publicId) {
    }

    private record Attempt(String idempotencyKey, long amount) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProviderConfiguration {

        @Bean
        @Primary
        ControllableRefundProvider controllableRefundProvider() {
            return new ControllableRefundProvider();
        }
    }

    static final class ControllableRefundProvider implements RefundProviderPort {

        private final AtomicInteger invocationCount = new AtomicInteger();
        private final AtomicReference<RefundProviderOutcome> outcome =
                new AtomicReference<>(RefundProviderOutcome.SUCCESS);
        private final AtomicBoolean blockNext = new AtomicBoolean();
        private volatile CountDownLatch invocationStarted = new CountDownLatch(0);
        private volatile CountDownLatch invocationRelease = new CountDownLatch(0);

        @Override
        public RefundProviderResult refund(RefundProviderRequest request) {
            invocationCount.incrementAndGet();
            RefundProviderOutcome currentOutcome = outcome.get();
            if (blockNext.compareAndSet(true, false)) {
                invocationStarted.countDown();
                awaitRelease();
            }
            return resultFor(currentOutcome, request.refundPublicId());
        }

        void respondWith(RefundProviderOutcome providerOutcome) {
            outcome.set(providerOutcome);
        }

        void blockNextInvocation() {
            invocationStarted = new CountDownLatch(1);
            invocationRelease = new CountDownLatch(1);
            blockNext.set(true);
        }

        boolean awaitInvocation() throws InterruptedException {
            return invocationStarted.await(10, TimeUnit.SECONDS);
        }

        void releaseInvocation() {
            invocationRelease.countDown();
        }

        int invocationCount() {
            return invocationCount.get();
        }

        void reset() {
            releaseInvocation();
            invocationCount.set(0);
            outcome.set(RefundProviderOutcome.SUCCESS);
            blockNext.set(false);
            invocationStarted = new CountDownLatch(0);
            invocationRelease = new CountDownLatch(0);
        }

        private void awaitRelease() {
            try {
                if (!invocationRelease.await(20, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting to release Refund provider");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "Interrupted while waiting to release Refund provider",
                        exception
                );
            }
        }

        private static RefundProviderResult resultFor(
                RefundProviderOutcome outcome,
                String refundPublicId
        ) {
            return switch (outcome) {
                case SUCCESS -> new RefundProviderResult(
                        "SIMULATOR",
                        outcome,
                        "provider_" + refundPublicId,
                        null,
                        null
                );
                case DECLINED -> new RefundProviderResult(
                        "SIMULATOR",
                        outcome,
                        null,
                        "REFUND_DECLINED",
                        "The provider declined the refund."
                );
                case TECHNICAL_FAILURE -> new RefundProviderResult(
                        "SIMULATOR",
                        outcome,
                        null,
                        "PROVIDER_UNAVAILABLE",
                        "The Refund provider operation did not complete."
                );
                case UNKNOWN -> new RefundProviderResult(
                        "SIMULATOR",
                        outcome,
                        null,
                        "PROVIDER_TIMEOUT",
                        "The Refund provider outcome is unknown."
                );
            };
        }
    }
}
