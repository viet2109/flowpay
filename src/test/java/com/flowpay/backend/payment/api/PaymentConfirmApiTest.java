package com.flowpay.backend.payment.api;

import com.flowpay.backend.common.money.Money;
import com.flowpay.backend.merchant.application.ApiKeyRepository;
import com.flowpay.backend.merchant.application.ApiKeySecretCodec;
import com.flowpay.backend.merchant.application.GeneratedApiKeySecret;
import com.flowpay.backend.merchant.application.MerchantRepository;
import com.flowpay.backend.merchant.domain.ApiKey;
import com.flowpay.backend.merchant.domain.Merchant;
import com.flowpay.backend.payment.application.PaymentIntentRepository;
import com.flowpay.backend.payment.application.PaymentProviderPort;
import com.flowpay.backend.payment.application.PaymentProviderRequest;
import com.flowpay.backend.payment.application.PaymentProviderResult;
import com.flowpay.backend.payment.domain.PaymentIntent;
import com.flowpay.backend.payment.domain.PaymentStatus;
import com.flowpay.backend.payment.domain.PaymentTransactionStatus;
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
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PaymentConfirmApiTest.ProviderTestConfiguration.class)
class PaymentConfirmApiTest extends PostgresIntegrationTest {

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
    private PaymentIntentRepository paymentIntentRepository;

    @Autowired
    private InspectingConfirmProvider paymentProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanData() {
        jdbcTemplate.update("""
                TRUNCATE TABLE ledger_entries, ledger_transactions, ledger_accounts,
                    idempotency_records, payment_transactions, payment_intents,
                    refresh_tokens, merchant_api_keys, merchant_members, merchants,
                    users RESTART IDENTITY CASCADE
                """);
        paymentProvider.reset();
    }

    @ParameterizedTest
    @EnumSource(ProviderOutcome.class)
    void shouldExposeOriginalAndReplayForEveryProviderOutcome(
            ProviderOutcome outcome
    ) throws Exception {
        String suffix = outcome.name().toLowerCase();
        StoredKey stored = createStoredKey(
                "mrc_confirm_api_" + suffix,
                "key_confirm_api_" + suffix
        );
        PaymentIntent payment = createPayment(stored, "pi_confirm_api_" + suffix);
        paymentProvider.respondWith(outcome);
        String idempotencyKey = "confirm-api-" + suffix;
        int expectedHttpStatus = outcome == ProviderOutcome.UNKNOWN ? 202 : 200;

        MvcResult original = performConfirm(
                stored.rawKey(),
                payment.publicId(),
                idempotencyKey
        )
                .andExpect(status().is(expectedHttpStatus))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().doesNotExist(PaymentIdempotencyHeaders.REPLAYED))
                .andExpect(jsonPath("$.data.paymentId").value(payment.publicId()))
                .andExpect(jsonPath("$.data.paymentStatus").value(paymentStatus(outcome).name()))
                .andExpect(jsonPath("$.data.transactionId").isString())
                .andExpect(jsonPath("$.data.transactionStatus")
                        .value(transactionStatus(outcome).name()))
                .andExpect(jsonPath("$.data.provider").value("SIMULATOR"))
                .andExpect(jsonPath("$.meta").doesNotExist())
                .andExpect(content().string(not(containsString("internalId"))))
                .andExpect(content().string(not(containsString("merchantId"))))
                .andExpect(content().string(not(containsString("executionId"))))
                .andExpect(content().string(not(containsString("requestHash"))))
                .andExpect(content().string(not(containsString("responsePayload"))))
                .andExpect(content().string(not(containsString("ltxn_"))))
                .andExpect(content().string(not(containsString("la_"))))
                .andExpect(content().string(not(containsString("SYSTEM_CLEARING:"))))
                .andExpect(content().string(not(containsString(
                        "MERCHANT_PAYABLE:" + stored.merchantId()
                ))))
                .andExpect(content().string(not(containsString(stored.rawKey()))))
                .andExpect(content().string(not(containsString(idempotencyKey))))
                .andReturn();
        assertOutcomeMetadata(original, outcome, payment.publicId());

        MvcResult replay = performConfirm(
                stored.rawKey(),
                payment.publicId(),
                idempotencyKey
        )
                .andExpect(status().is(expectedHttpStatus))
                .andExpect(header().string(PaymentIdempotencyHeaders.REPLAYED, "true"))
                .andReturn();

        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(original.getResponse().getContentAsString());
        assertThat(paymentProvider.invocationCount()).isEqualTo(1);
        assertThat(paymentProvider.transactionActive()).isFalse();
        assertThat(countRows("payment_transactions", stored.merchantId())).isEqualTo(1);
        assertThat(countRows("idempotency_records", stored.merchantId())).isEqualTo(1);
    }

    @Test
    void shouldRequireAValidIdempotencyKeyBeforeConfirmation() throws Exception {
        StoredKey stored = createStoredKey("mrc_confirm_key", "key_confirm_key");
        PaymentIntent payment = createPayment(stored, "pi_confirm_key");
        String path = path(payment.publicId());

        mockMvc.perform(post(path)
                        .header(HttpHeaders.AUTHORIZATION, bearer(stored.rawKey())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"))
                .andExpect(jsonPath("$.type").value(
                        "https://flowpay.dev/problems/idempotency-key-required"
                ))
                .andExpect(jsonPath("$.requestId").isString())
                .andExpect(safeProblem(stored.rawKey()));
        performConfirm(stored.rawKey(), payment.publicId(), "   ")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"))
                .andExpect(safeProblem(stored.rawKey()));
        String oversized = "k".repeat(256);
        performConfirm(stored.rawKey(), payment.publicId(), oversized)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(content().string(not(containsString(oversized))))
                .andExpect(safeProblem(stored.rawKey(), oversized));

        assertThat(paymentProvider.invocationCount()).isZero();
        assertThat(countRows("payment_transactions", stored.merchantId())).isZero();
        assertThat(countRows("idempotency_records", stored.merchantId())).isZero();
    }

    @Test
    void shouldRejectAKeyReusedForAnotherPayment() throws Exception {
        StoredKey stored = createStoredKey("mrc_confirm_reused", "key_confirm_reused");
        PaymentIntent first = createPayment(stored, "pi_confirm_reused_first");
        PaymentIntent second = createPayment(stored, "pi_confirm_reused_second");

        performConfirm(stored.rawKey(), first.publicId(), "confirm-reused")
                .andExpect(status().isOk());
        performConfirm(stored.rawKey(), second.publicId(), "confirm-reused")
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"))
                .andExpect(jsonPath("$.type").value(
                        "https://flowpay.dev/problems/idempotency-key-reused"
                ))
                .andExpect(safeProblem(stored.rawKey(), "confirm-reused"));

        assertThat(paymentProvider.invocationCount()).isEqualTo(1);
        assertThat(countRows("payment_transactions", stored.merchantId())).isEqualTo(1);
        assertThat(countRows("idempotency_records", stored.merchantId())).isEqualTo(1);
    }

    @Test
    void shouldRejectInvalidAndRevokedApiKeysBeforeConfirmation() throws Exception {
        mockMvc.perform(post(path("pi_confirm_missing_key"))
                        .header(PaymentIdempotencyHeaders.KEY, "confirm-missing-api-key"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
                .andExpect(safeProblem("confirm-missing-api-key"));

        GeneratedApiKeySecret unknown = secretCodec.generate();
        mockMvc.perform(post(path("pi_confirm_unknown_key"))
                        .header(HttpHeaders.AUTHORIZATION, bearer(unknown.rawKey()))
                        .header(PaymentIdempotencyHeaders.KEY, "confirm-unknown-key"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_API_KEY"))
                .andExpect(content().string(not(containsString(unknown.rawKey()))))
                .andExpect(safeProblem(unknown.rawKey(), "confirm-unknown-key"));

        StoredKey revoked = createStoredKey("mrc_confirm_revoked", "key_confirm_revoked");
        PaymentIntent payment = createPayment(revoked, "pi_confirm_revoked");
        ApiKey apiKey = apiKeyRepository.findByPublicId(revoked.apiKeyPublicId()).orElseThrow();
        apiKey.revoke(Instant.now());
        apiKeyRepository.save(apiKey);

        performConfirm(revoked.rawKey(), payment.publicId(), "confirm-revoked")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("API_KEY_REVOKED"))
                .andExpect(content().string(not(containsString(revoked.rawKey()))))
                .andExpect(safeProblem(revoked.rawKey(), "confirm-revoked"));

        assertThat(paymentProvider.invocationCount()).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM idempotency_records",
                Integer.class
        )).isZero();
    }

    @Test
    void shouldHideCrossMerchantPaymentAsNotFound() throws Exception {
        StoredKey owner = createStoredKey("mrc_confirm_owner", "key_confirm_owner");
        StoredKey other = createStoredKey("mrc_confirm_other", "key_confirm_other");
        PaymentIntent payment = createPayment(owner, "pi_confirm_owned");

        performConfirm(other.rawKey(), payment.publicId(), "confirm-cross-merchant")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYMENT_NOT_FOUND"));

        assertThat(paymentProvider.invocationCount()).isZero();
        assertThat(countRows("payment_transactions", owner.merchantId())).isZero();
        assertThat(countRows("idempotency_records", other.merchantId())).isZero();
    }

    @Test
    void concurrentDuplicateShouldCallProviderOnlyOnce() throws Exception {
        StoredKey stored = createStoredKey("mrc_confirm_concurrent", "key_confirm_concurrent");
        PaymentIntent payment = createPayment(stored, "pi_confirm_concurrent");
        paymentProvider.blockNextInvocation();
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            Future<MvcResult> original = executor.submit(() -> performConfirm(
                    stored.rawKey(),
                    payment.publicId(),
                    "confirm-concurrent"
            ).andReturn());
            assertThat(paymentProvider.awaitInvocation()).isTrue();

            performConfirm(stored.rawKey(), payment.publicId(), "confirm-concurrent")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code")
                            .value("IDEMPOTENCY_REQUEST_IN_PROGRESS"))
                    .andExpect(jsonPath("$.type").value(
                            "https://flowpay.dev/problems/idempotency-request-in-progress"
                    ))
                    .andExpect(jsonPath("$.requestId").isString())
                    .andExpect(safeProblem(stored.rawKey(), "confirm-concurrent"));

            paymentProvider.releaseInvocation();
            assertThat(original.get(20, TimeUnit.SECONDS).getResponse().getStatus())
                    .isEqualTo(200);
        } finally {
            paymentProvider.releaseInvocation();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(paymentProvider.invocationCount()).isEqualTo(1);
        assertThat(countRows("payment_transactions", stored.merchantId())).isEqualTo(1);
        assertThat(countRows("idempotency_records", stored.merchantId())).isEqualTo(1);
    }

    private org.springframework.test.web.servlet.ResultActions performConfirm(
            String rawApiKey,
            String paymentPublicId,
            String idempotencyKey
    ) throws Exception {
        return mockMvc.perform(post(path(paymentPublicId))
                .header(HttpHeaders.AUTHORIZATION, bearer(rawApiKey))
                .header(PaymentIdempotencyHeaders.KEY, idempotencyKey));
    }

    private void assertOutcomeMetadata(
            MvcResult result,
            ProviderOutcome outcome,
            String paymentPublicId
    ) throws Exception {
        var body = objectMapper.readTree(
                result.getResponse().getContentAsByteArray()
        ).path("data");
        PaymentProviderResult providerResult = providerResult(outcome, paymentPublicId);
        assertThat(body.path("providerTransactionId").stringValue())
                .isEqualTo(providerResult.providerTransactionId());
        assertThat(body.path("failureCode").stringValue())
                .isEqualTo(providerResult.failureCode());
        assertThat(body.path("failureMessage").stringValue())
                .isEqualTo(providerResult.failureMessage());
    }

    private StoredKey createStoredKey(String merchantPublicId, String apiKeyPublicId) {
        Merchant merchant = merchantRepository.save(Merchant.create(
                merchantPublicId,
                "Payment Confirm Store",
                CREATED_AT
        ));
        GeneratedApiKeySecret secret = secretCodec.generate();
        ApiKey apiKey = apiKeyRepository.save(ApiKey.create(
                apiKeyPublicId,
                merchant.id(),
                "Payment confirm key",
                secret.prefix(),
                secret.digest(),
                null,
                CREATED_AT
        ));
        return new StoredKey(merchant.id(), apiKey.publicId(), secret.rawKey());
    }

    private PaymentIntent createPayment(StoredKey stored, String paymentPublicId) {
        return paymentIntentRepository.save(PaymentIntent.create(
                paymentPublicId,
                stored.merchantId(),
                "ORDER-" + paymentPublicId,
                "Confirm " + paymentPublicId,
                Money.of(50_000L, "VND"),
                CREATED_AT
        ));
    }

    private int countRows(String table, long merchantId) {
        if (!table.equals("payment_transactions")
                && !table.equals("idempotency_records")) {
            throw new IllegalArgumentException("Unsupported table");
        }
        if (table.equals("idempotency_records")) {
            return jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM idempotency_records WHERE merchant_id = ?",
                    Integer.class,
                    merchantId
            );
        }
        return jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM payment_transactions pt
                JOIN payment_intents pi ON pi.id = pt.payment_intent_id
                WHERE pi.merchant_id = ?
                """, Integer.class, merchantId);
    }

    private static String path(String paymentPublicId) {
        return "/api/v1/payment-intents/" + paymentPublicId + "/confirm";
    }

    private static String bearer(String rawKey) {
        return "Bearer " + rawKey;
    }

    private static ResultMatcher safeProblem(String... sensitiveValues) {
        return result -> assertThat(result.getResponse().getContentAsString())
                .doesNotContain(sensitiveValues)
                .doesNotContain(
                        "internalId",
                        "merchantId",
                        "executionId",
                        "requestHash",
                        "responsePayload",
                        "IdempotencyRecordEntity",
                        "idempotency_records",
                        "uq_idempotency_records_scope",
                        "LedgerAccountEntity",
                        "LedgerTransactionEntity",
                        "ledger_accounts",
                        "ledger_transactions",
                        "ledger_entries",
                        "uq_ledger_transactions_business_reference",
                        "ltxn_",
                        "la_",
                        "SYSTEM_CLEARING:",
                        "MERCHANT_PAYABLE:",
                        "DataIntegrityViolationException",
                        "org.hibernate",
                        "org.postgresql"
                );
    }

    private static PaymentStatus paymentStatus(ProviderOutcome outcome) {
        return switch (outcome) {
            case SUCCESS -> PaymentStatus.SUCCEEDED;
            case DECLINED, TECHNICAL_FAILURE -> PaymentStatus.FAILED;
            case UNKNOWN -> PaymentStatus.PROCESSING;
        };
    }

    private static PaymentTransactionStatus transactionStatus(ProviderOutcome outcome) {
        return switch (outcome) {
            case SUCCESS -> PaymentTransactionStatus.SUCCEEDED;
            case DECLINED, TECHNICAL_FAILURE -> PaymentTransactionStatus.FAILED;
            case UNKNOWN -> PaymentTransactionStatus.UNKNOWN;
        };
    }

    private static PaymentProviderResult providerResult(
            ProviderOutcome outcome,
            String paymentPublicId
    ) {
        return switch (outcome) {
            case SUCCESS -> new PaymentProviderResult(
                    "SIMULATOR", outcome, "sim_" + paymentPublicId, null, null
            );
            case DECLINED -> new PaymentProviderResult(
                    "SIMULATOR",
                    outcome,
                    "sim_" + paymentPublicId,
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

    private record StoredKey(long merchantId, String apiKeyPublicId, String rawKey) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProviderTestConfiguration {

        @Bean
        @Primary
        InspectingConfirmProvider inspectingConfirmProvider() {
            return new InspectingConfirmProvider();
        }
    }

    static final class InspectingConfirmProvider implements PaymentProviderPort {

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
