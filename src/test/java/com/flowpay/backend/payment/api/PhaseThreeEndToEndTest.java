package com.flowpay.backend.payment.api;

import com.flowpay.backend.merchant.application.ApiKeyRepository;
import com.flowpay.backend.merchant.application.ApiKeySecretCodec;
import com.flowpay.backend.merchant.application.GeneratedApiKeySecret;
import com.flowpay.backend.merchant.application.MerchantRepository;
import com.flowpay.backend.merchant.domain.ApiKey;
import com.flowpay.backend.merchant.domain.Merchant;
import com.flowpay.backend.payment.application.PaymentProviderPort;
import com.flowpay.backend.payment.application.PaymentProviderRequest;
import com.flowpay.backend.payment.application.PaymentProviderResult;
import com.flowpay.backend.payment.domain.PaymentStatus;
import com.flowpay.backend.payment.domain.ProviderOutcome;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PhaseThreeEndToEndTest.ProviderConfiguration.class)
class PhaseThreeEndToEndTest extends PostgresIntegrationTest {

    private static final String CREATE_PATH = "/api/v1/payment-intents";
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
    private InspectingProvider provider;

    @BeforeEach
    void cleanData() {
        provider.reset();
        jdbcTemplate.update("""
                TRUNCATE TABLE idempotency_records, payment_transactions, payment_intents,
                    refresh_tokens, merchant_api_keys, merchant_members, merchants, users
                    RESTART IDENTITY CASCADE
                """);
    }

    @Test
    void concurrentSameCreateShouldHaveOneExecutionOwnerAndOnePayment() throws Exception {
        StoredKey stored = createStoredKey("mrc_e2e_create_concurrent", "key_e2e_create");
        String idempotencyKey = "e2e-create-concurrent";
        String body = requestBody(50_000L);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<MvcResult> first = executor.submit(() -> concurrentCreate(
                    stored,
                    idempotencyKey,
                    body,
                    ready,
                    start
            ));
            Future<MvcResult> second = executor.submit(() -> concurrentCreate(
                    stored,
                    idempotencyKey,
                    body,
                    ready,
                    start
            ));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<MvcResult> results = List.of(
                    first.get(20, TimeUnit.SECONDS),
                    second.get(20, TimeUnit.SECONDS)
            );
            List<MvcResult> owners = results.stream()
                    .filter(result -> result.getResponse().getStatus() == 201)
                    .filter(result -> result.getResponse().getHeader(
                            PaymentIdempotencyHeaders.REPLAYED
                    ) == null)
                    .toList();
            assertThat(owners).hasSize(1);

            MvcResult owner = owners.getFirst();
            MvcResult duplicate = results.stream()
                    .filter(result -> result != owner)
                    .findFirst()
                    .orElseThrow();
            assertCreateDuplicateIsReplayOrInProgress(owner, duplicate);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(countPayments(stored.merchantId())).isEqualTo(1);
        assertThat(countIdempotency(
                stored.merchantId(),
                "PAYMENT_INTENT_CREATE",
                idempotencyKey
        )).isEqualTo(1);
    }

    @Test
    void sameCreateKeyShouldBeMerchantScopedAndRejectDifferentFingerprint() throws Exception {
        StoredKey firstMerchant = createStoredKey("mrc_e2e_scope_a", "key_e2e_scope_a");
        StoredKey secondMerchant = createStoredKey("mrc_e2e_scope_b", "key_e2e_scope_b");
        String sharedKey = "e2e-merchant-scope";

        MvcResult first = performCreate(
                firstMerchant,
                sharedKey,
                requestBody(50_000L)
        ).andExpect(status().isCreated()).andReturn();
        MvcResult second = performCreate(
                secondMerchant,
                sharedKey,
                requestBody(60_000L)
        ).andExpect(status().isCreated()).andReturn();

        assertThat(paymentId(first)).isNotEqualTo(paymentId(second));
        performCreate(firstMerchant, sharedKey, requestBody(50_001L))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        assertThat(countPayments(firstMerchant.merchantId())).isEqualTo(1);
        assertThat(countPayments(secondMerchant.merchantId())).isEqualTo(1);
        assertThat(countIdempotency(
                firstMerchant.merchantId(),
                "PAYMENT_INTENT_CREATE",
                sharedKey
        )).isEqualTo(1);
        assertThat(countIdempotency(
                secondMerchant.merchantId(),
                "PAYMENT_INTENT_CREATE",
                sharedKey
        )).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(ProviderOutcome.class)
    void operationScopedReplaysShouldPreserveSnapshotsForEveryProviderOutcome(
            ProviderOutcome outcome
    ) throws Exception {
        StoredKey stored = createStoredKey(
                "mrc_e2e_snapshot_" + outcome.name().toLowerCase(),
                "key_e2e_snapshot_" + outcome.name().toLowerCase()
        );
        String sharedKey = "e2e-operation-" + outcome.name().toLowerCase();
        String body = requestBody(75_000L);
        MvcResult originalCreate = performCreate(stored, sharedKey, body)
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist(PaymentIdempotencyHeaders.REPLAYED))
                .andExpect(jsonPath("$.data.status").value("CREATED"))
                .andReturn();
        String paymentId = paymentId(originalCreate);

        provider.respondWith(outcome);
        int expectedStatus = confirmHttpStatus(outcome);
        MvcResult originalConfirm = performConfirm(stored, paymentId, sharedKey)
                .andExpect(status().is(expectedStatus))
                .andExpect(header().doesNotExist(PaymentIdempotencyHeaders.REPLAYED))
                .andExpect(jsonPath("$.data.paymentStatus")
                        .value(paymentStatus(outcome).name()))
                .andReturn();

        MvcResult createReplay = performCreate(stored, sharedKey, body)
                .andExpect(status().isCreated())
                .andExpect(header().string(PaymentIdempotencyHeaders.REPLAYED, "true"))
                .andExpect(header().string(
                        HttpHeaders.LOCATION,
                        originalCreate.getResponse().getHeader(HttpHeaders.LOCATION)
                ))
                .andReturn();
        MvcResult confirmReplay = performConfirm(stored, paymentId, sharedKey)
                .andExpect(status().is(expectedStatus))
                .andExpect(header().string(PaymentIdempotencyHeaders.REPLAYED, "true"))
                .andReturn();

        assertThat(createReplay.getResponse().getContentAsString())
                .isEqualTo(originalCreate.getResponse().getContentAsString());
        assertThat(confirmReplay.getResponse().getContentAsString())
                .isEqualTo(originalConfirm.getResponse().getContentAsString());
        assertThat(currentPaymentStatus(paymentId)).isEqualTo(paymentStatus(outcome).name());
        assertThat(provider.invocationCount()).isEqualTo(1);
        assertThat(provider.transactionActive()).isFalse();
        assertThat(countPayments(stored.merchantId())).isEqualTo(1);
        assertThat(countTransactions(stored.merchantId())).isEqualTo(1);
        assertThat(countIdempotency(stored.merchantId(), "PAYMENT_INTENT_CREATE", sharedKey))
                .isEqualTo(1);
        assertThat(countIdempotency(stored.merchantId(), "PAYMENT_INTENT_CONFIRM", sharedKey))
                .isEqualTo(1);
    }

    @Test
    void concurrentSameConfirmShouldCallProviderOnceThenReplay() throws Exception {
        StoredKey stored = createStoredKey("mrc_e2e_confirm_concurrent", "key_e2e_confirm");
        MvcResult created = performCreate(
                stored,
                "e2e-confirm-create",
                requestBody(80_000L)
        ).andExpect(status().isCreated()).andReturn();
        String paymentId = paymentId(created);
        String confirmKey = "e2e-confirm-concurrent";
        provider.blockNextInvocation();
        ExecutorService executor = Executors.newSingleThreadExecutor();

        MvcResult original;
        try {
            Future<MvcResult> owner = executor.submit(() -> performConfirm(
                    stored,
                    paymentId,
                    confirmKey
            ).andReturn());
            assertThat(provider.awaitInvocation()).isTrue();

            performConfirm(stored, paymentId, confirmKey)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code")
                            .value("IDEMPOTENCY_REQUEST_IN_PROGRESS"));

            provider.releaseInvocation();
            original = owner.get(20, TimeUnit.SECONDS);
            assertThat(original.getResponse().getStatus()).isEqualTo(200);
        } finally {
            provider.releaseInvocation();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        MvcResult replay = performConfirm(stored, paymentId, confirmKey)
                .andExpect(status().isOk())
                .andExpect(header().string(PaymentIdempotencyHeaders.REPLAYED, "true"))
                .andReturn();
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(original.getResponse().getContentAsString());
        assertThat(provider.invocationCount()).isEqualTo(1);
        assertThat(provider.transactionActive()).isFalse();
        assertThat(countTransactions(stored.merchantId())).isEqualTo(1);
        assertThat(countIdempotency(
                stored.merchantId(),
                "PAYMENT_INTENT_CONFIRM",
                confirmKey
        )).isEqualTo(1);
    }

    @Test
    void sameConfirmKeyForDifferentPaymentsShouldBeRejected() throws Exception {
        StoredKey stored = createStoredKey("mrc_e2e_confirm_reused", "key_e2e_reused");
        String firstPayment = paymentId(performCreate(
                stored,
                "e2e-first-create",
                requestBody(20_000L)
        ).andExpect(status().isCreated()).andReturn());
        String secondPayment = paymentId(performCreate(
                stored,
                "e2e-second-create",
                requestBody(30_000L)
        ).andExpect(status().isCreated()).andReturn());
        String confirmKey = "e2e-confirm-reused";

        performConfirm(stored, firstPayment, confirmKey)
                .andExpect(status().isOk());
        performConfirm(stored, secondPayment, confirmKey)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        assertThat(provider.invocationCount()).isEqualTo(1);
        assertThat(countTransactions(stored.merchantId())).isEqualTo(1);
        assertThat(countIdempotency(
                stored.merchantId(),
                "PAYMENT_INTENT_CONFIRM",
                confirmKey
        )).isEqualTo(1);
    }

    @Test
    void flywayShouldHaveAppliedTheCleanMigrationChain() {
        assertThat(jdbcTemplate.queryForList("""
                SELECT version::integer
                FROM flyway_schema_history
                WHERE success = true
                ORDER BY installed_rank
                """, Integer.class)).startsWith(1, 2, 3, 4, 5, 6, 7);
        assertThat(jdbcTemplate.queryForObject("SELECT version()", String.class))
                .contains("PostgreSQL");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM pg_indexes
                WHERE schemaname = 'public'
                  AND tablename = 'idempotency_records'
                  AND indexname = 'ix_idempotency_records_status_expires_at'
                """, Integer.class)).isEqualTo(1);
    }

    private MvcResult concurrentCreate(
            StoredKey stored,
            String idempotencyKey,
            String body,
            CountDownLatch ready,
            CountDownLatch start
    ) throws Exception {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Concurrent create did not start in time");
        }
        return performCreate(stored, idempotencyKey, body).andReturn();
    }

    private void assertCreateDuplicateIsReplayOrInProgress(
            MvcResult owner,
            MvcResult duplicate
    ) throws Exception {
        if (duplicate.getResponse().getStatus() == 201) {
            assertThat(duplicate.getResponse().getHeader(PaymentIdempotencyHeaders.REPLAYED))
                    .isEqualTo("true");
            assertThat(duplicate.getResponse().getHeader(HttpHeaders.LOCATION))
                    .isEqualTo(owner.getResponse().getHeader(HttpHeaders.LOCATION));
            assertThat(duplicate.getResponse().getContentAsString())
                    .isEqualTo(owner.getResponse().getContentAsString());
            return;
        }
        assertThat(duplicate.getResponse().getStatus()).isEqualTo(409);
        assertThat(objectMapper.readTree(duplicate.getResponse().getContentAsByteArray())
                .path("code")
                .stringValue()).isEqualTo("IDEMPOTENCY_REQUEST_IN_PROGRESS");
    }

    private org.springframework.test.web.servlet.ResultActions performCreate(
            StoredKey stored,
            String idempotencyKey,
            String body
    ) throws Exception {
        return mockMvc.perform(post(CREATE_PATH)
                .header(HttpHeaders.AUTHORIZATION, bearer(stored.rawKey()))
                .header(PaymentIdempotencyHeaders.KEY, idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private org.springframework.test.web.servlet.ResultActions performConfirm(
            StoredKey stored,
            String paymentId,
            String idempotencyKey
    ) throws Exception {
        return mockMvc.perform(post(CREATE_PATH + "/" + paymentId + "/confirm")
                .header(HttpHeaders.AUTHORIZATION, bearer(stored.rawKey()))
                .header(PaymentIdempotencyHeaders.KEY, idempotencyKey));
    }

    private StoredKey createStoredKey(String merchantPublicId, String apiKeyPublicId) {
        Merchant merchant = merchantRepository.save(Merchant.create(
                merchantPublicId,
                "Phase Three Store",
                CREATED_AT
        ));
        GeneratedApiKeySecret secret = secretCodec.generate();
        apiKeyRepository.save(ApiKey.create(
                apiKeyPublicId,
                merchant.id(),
                "Phase three key",
                secret.prefix(),
                secret.digest(),
                null,
                CREATED_AT
        ));
        return new StoredKey(merchant.id(), secret.rawKey());
    }

    private String paymentId(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray())
                .path("data")
                .path("id")
                .stringValue();
    }

    private int countPayments(long merchantId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_intents WHERE merchant_id = ?",
                Integer.class,
                merchantId
        );
    }

    private int countTransactions(long merchantId) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM payment_transactions payment_transaction
                JOIN payment_intents payment
                  ON payment.id = payment_transaction.payment_intent_id
                WHERE payment.merchant_id = ?
                """, Integer.class, merchantId);
    }

    private int countIdempotency(long merchantId, String operation, String key) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM idempotency_records
                WHERE merchant_id = ?
                  AND operation = ?
                  AND idempotency_key = ?
                """, Integer.class, merchantId, operation, key);
    }

    private String currentPaymentStatus(String paymentId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM payment_intents WHERE public_id = ?",
                String.class,
                paymentId
        );
    }

    private static String requestBody(long amount) {
        return """
                {
                  "amount": %d,
                  "currency": "VND",
                  "orderId": "ORDER-E2E",
                  "description": "Phase three end-to-end payment"
                }
                """.formatted(amount);
    }

    private static String bearer(String rawKey) {
        return "Bearer " + rawKey;
    }

    private static int confirmHttpStatus(ProviderOutcome outcome) {
        return outcome == ProviderOutcome.UNKNOWN ? 202 : 200;
    }

    private static PaymentStatus paymentStatus(ProviderOutcome outcome) {
        return switch (outcome) {
            case SUCCESS -> PaymentStatus.SUCCEEDED;
            case DECLINED, TECHNICAL_FAILURE -> PaymentStatus.FAILED;
            case UNKNOWN -> PaymentStatus.PROCESSING;
        };
    }

    private static PaymentProviderResult providerResult(
            ProviderOutcome outcome,
            String paymentId
    ) {
        return switch (outcome) {
            case SUCCESS -> new PaymentProviderResult(
                    "SIMULATOR", outcome, "provider_" + paymentId, null, null
            );
            case DECLINED -> new PaymentProviderResult(
                    "SIMULATOR",
                    outcome,
                    null,
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

    private record StoredKey(long merchantId, String rawKey) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProviderConfiguration {

        @Bean
        @Primary
        InspectingProvider inspectingProvider() {
            return new InspectingProvider();
        }
    }

    static final class InspectingProvider implements PaymentProviderPort {

        private final AtomicInteger invocationCount = new AtomicInteger();
        private volatile ProviderOutcome outcome = ProviderOutcome.SUCCESS;
        private volatile boolean transactionActive;
        private volatile CountDownLatch invocationStarted = new CountDownLatch(0);
        private volatile CountDownLatch invocationRelease = new CountDownLatch(0);

        @Override
        public PaymentProviderResult charge(PaymentProviderRequest request) {
            invocationCount.incrementAndGet();
            transactionActive = TransactionSynchronizationManager.isActualTransactionActive();
            CountDownLatch started = invocationStarted;
            CountDownLatch release = invocationRelease;
            started.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting to release provider");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Provider invocation was interrupted", exception);
            }
            return providerResult(outcome, request.paymentPublicReference());
        }

        void respondWith(ProviderOutcome outcome) {
            this.outcome = outcome;
        }

        void blockNextInvocation() {
            invocationStarted = new CountDownLatch(1);
            invocationRelease = new CountDownLatch(1);
        }

        boolean awaitInvocation() throws InterruptedException {
            return invocationStarted.await(10, TimeUnit.SECONDS);
        }

        void releaseInvocation() {
            invocationRelease.countDown();
        }

        void reset() {
            invocationCount.set(0);
            outcome = ProviderOutcome.SUCCESS;
            transactionActive = false;
            invocationStarted = new CountDownLatch(0);
            invocationRelease = new CountDownLatch(0);
        }

        int invocationCount() {
            return invocationCount.get();
        }

        boolean transactionActive() {
            return transactionActive;
        }
    }
}
