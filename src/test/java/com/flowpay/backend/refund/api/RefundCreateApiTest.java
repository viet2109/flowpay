package com.flowpay.backend.refund.api;

import com.flowpay.backend.common.security.MerchantApiPrincipal;
import com.flowpay.backend.idempotency.domain.IdempotencyKey;
import com.flowpay.backend.merchant.application.ApiKeyRepository;
import com.flowpay.backend.merchant.application.ApiKeySecretCodec;
import com.flowpay.backend.merchant.application.GeneratedApiKeySecret;
import com.flowpay.backend.merchant.application.MerchantRepository;
import com.flowpay.backend.merchant.domain.ApiKey;
import com.flowpay.backend.merchant.domain.Merchant;
import com.flowpay.backend.refund.application.PrepareRefundCommand;
import com.flowpay.backend.refund.application.PrepareRefundService;
import com.flowpay.backend.refund.application.RefundProviderPort;
import com.flowpay.backend.refund.application.RefundProviderRequest;
import com.flowpay.backend.refund.application.RefundProviderResult;
import com.flowpay.backend.refund.domain.RefundProviderOutcome;
import com.flowpay.backend.refund.domain.RefundReason;
import com.flowpay.backend.refund.domain.RefundStatus;
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
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(RefundCreateApiTest.ProviderTestConfiguration.class)
class RefundCreateApiTest extends PostgresIntegrationTest {

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
    private PrepareRefundService preparationService;

    @Autowired
    private ControllableRefundProvider refundProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanData() {
        jdbcTemplate.update("""
                TRUNCATE TABLE refunds, idempotency_records, payment_transactions,
                    payment_intents, refresh_tokens, merchant_api_keys, merchant_members,
                    merchants, users RESTART IDENTITY CASCADE
                """);
        refundProvider.reset();
    }

    @ParameterizedTest
    @EnumSource(RefundProviderOutcome.class)
    void shouldCreateAndReplayExactPublicResponseForEveryOutcome(
            RefundProviderOutcome outcome
    ) throws Exception {
        String suffix = outcome.name().toLowerCase();
        StoredKey stored = createStoredKey(
                "mrc_refund_api_" + suffix,
                "key_refund_api_" + suffix
        );
        PaymentFixture payment = insertPayment(
                stored,
                "outcome_" + suffix,
                "SUCCEEDED",
                1_000L,
                "USD"
        );
        refundProvider.respondWith(outcome);
        String idempotencyKey = "refund-api-" + suffix;
        int expectedStatus = outcome == RefundProviderOutcome.UNKNOWN ? 202 : 201;

        MvcResult original = performCreate(
                stored.rawKey(),
                payment.publicId(),
                idempotencyKey,
                requestBody(400L, "  CUSTOMER_REQUEST  ")
        )
                .andExpect(status().is(expectedStatus))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().doesNotExist(RefundIdempotencyHeaders.REPLAYED))
                .andExpect(jsonPath("$.data.id").isString())
                .andExpect(jsonPath("$.data.paymentId").value(payment.publicId()))
                .andExpect(jsonPath("$.data.amount").value(400L))
                .andExpect(jsonPath("$.data.currency").value("USD"))
                .andExpect(jsonPath("$.data.status").value(refundStatus(outcome).name()))
                .andExpect(jsonPath("$.data.reason").value("CUSTOMER_REQUEST"))
                .andExpect(jsonPath("$.data.provider").value("SIMULATOR"))
                .andExpect(jsonPath("$.data.createdAt").isString())
                .andExpect(jsonPath("$.data.updatedAt").isString())
                .andExpect(jsonPath("$.meta").doesNotExist())
                .andExpect(content().string(not(containsString("internalId"))))
                .andExpect(content().string(not(containsString("merchantId"))))
                .andExpect(content().string(not(containsString("executionId"))))
                .andExpect(content().string(not(containsString("requestHash"))))
                .andExpect(content().string(not(containsString("responsePayload"))))
                .andExpect(content().string(not(containsString("rawProviderPayload"))))
                .andExpect(content().string(not(containsString("\"version\""))))
                .andExpect(content().string(not(containsString(stored.rawKey()))))
                .andExpect(content().string(not(containsString(idempotencyKey))))
                .andReturn();

        String refundId = responseId(original);
        String location = "/api/v1/refunds/" + refundId;
        assertThat(original.getResponse().getHeader(HttpHeaders.LOCATION)).isEqualTo(location);
        assertOutcomeFields(original, outcome, refundId);
        Map<String, Object> beforeReplay = paymentCapacity(payment.internalId());

        MvcResult replay = performCreate(
                stored.rawKey(),
                payment.publicId(),
                idempotencyKey,
                requestBody(400L, "CUSTOMER_REQUEST")
        )
                .andExpect(status().is(expectedStatus))
                .andExpect(header().string(RefundIdempotencyHeaders.REPLAYED, "true"))
                .andExpect(header().string(HttpHeaders.LOCATION, location))
                .andReturn();

        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(original.getResponse().getContentAsString());
        assertThat(paymentCapacity(payment.internalId())).isEqualTo(beforeReplay);
        assertThat(refundProvider.invocationCount()).isEqualTo(1);
        assertThat(countRefunds(stored.merchantId())).isEqualTo(1);
    }

    @Test
    void shouldNormalizeBlankReasonAndRejectFieldsOutsideRequestContract() throws Exception {
        StoredKey stored = createStoredKey("mrc_refund_contract", "key_refund_contract");
        PaymentFixture payment = insertPayment(
                stored,
                "contract",
                "SUCCEEDED",
                1_000L,
                "VND"
        );

        performCreate(
                stored.rawKey(),
                payment.publicId(),
                "refund-client-currency",
                """
                        {
                          "amount": 200,
                          "reason": "CUSTOMER_REQUEST",
                          "currency": "USD"
                        }
                        """
        )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(safeProblem(stored.rawKey(), "refund-client-currency"));

        MvcResult blankReason = performCreate(
                stored.rawKey(),
                payment.publicId(),
                "refund-blank-reason",
                requestBody(200L, "   ")
        )
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.reason").value(nullValue()))
                .andExpect(jsonPath("$.data.currency").value("VND"))
                .andReturn();

        assertThat(blankReason.getResponse().getHeader(HttpHeaders.LOCATION))
                .isEqualTo("/api/v1/refunds/" + responseId(blankReason));
        assertThat(refundProvider.invocationCount()).isEqualTo(1);
        assertThat(countRefunds(stored.merchantId())).isEqualTo(1);
    }

    @Test
    void shouldValidateIdempotencyKeyAndRequestBeforeWriting() throws Exception {
        StoredKey stored = createStoredKey("mrc_refund_validation", "key_refund_validation");
        PaymentFixture payment = insertPayment(
                stored,
                "validation",
                "SUCCEEDED",
                1_000L,
                "VND"
        );
        String path = path(payment.publicId());

        mockMvc.perform(post(path)
                        .header(HttpHeaders.AUTHORIZATION, bearer(stored.rawKey()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(200L, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
        performCreate(stored.rawKey(), payment.publicId(), "   ", requestBody(200L, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
        String oversizedKey = "k".repeat(256);
        performCreate(
                stored.rawKey(),
                payment.publicId(),
                oversizedKey,
                requestBody(200L, null)
        )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(safeProblem(oversizedKey));
        performCreate(
                stored.rawKey(),
                payment.publicId(),
                "refund-zero",
                requestBody(0L, null)
        )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("amount"));
        performCreate(
                stored.rawKey(),
                payment.publicId(),
                "refund-negative",
                requestBody(-1L, null)
        )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        performCreate(
                stored.rawKey(),
                payment.publicId(),
                "refund-long-reason",
                requestBody(200L, "r".repeat(256))
        )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("reason"));

        assertThat(refundProvider.invocationCount()).isZero();
        assertThat(countRefunds(stored.merchantId())).isZero();
        assertThat(countIdempotency(stored.merchantId())).isZero();
    }

    @Test
    void shouldRejectReusedAndInProgressIdempotencyRequests() throws Exception {
        StoredKey reused = createStoredKey("mrc_refund_reused", "key_refund_reused");
        PaymentFixture reusedPayment = insertPayment(
                reused,
                "reused",
                "SUCCEEDED",
                1_000L,
                "VND"
        );
        performCreate(
                reused.rawKey(),
                reusedPayment.publicId(),
                "refund-reused",
                requestBody(200L, "CUSTOMER_REQUEST")
        ).andExpect(status().isCreated());
        performCreate(
                reused.rawKey(),
                reusedPayment.publicId(),
                "refund-reused",
                requestBody(201L, "CUSTOMER_REQUEST")
        )
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"))
                .andExpect(safeProblem(reused.rawKey(), "refund-reused"));

        StoredKey processing = createStoredKey(
                "mrc_refund_processing",
                "key_refund_processing"
        );
        PaymentFixture processingPayment = insertPayment(
                processing,
                "processing",
                "SUCCEEDED",
                1_000L,
                "VND"
        );
        preparationService.prepare(new PrepareRefundCommand(
                new MerchantApiPrincipal(
                        processing.merchantPublicId(),
                        processing.apiKeyPublicId()
                ),
                IdempotencyKey.of("refund-processing"),
                processingPayment.publicId(),
                200L,
                RefundReason.of("CUSTOMER_REQUEST")
        ));

        performCreate(
                processing.rawKey(),
                processingPayment.publicId(),
                "refund-processing",
                requestBody(200L, "CUSTOMER_REQUEST")
        )
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_REQUEST_IN_PROGRESS"))
                .andExpect(safeProblem(processing.rawKey(), "refund-processing"));

        assertThat(refundProvider.invocationCount()).isEqualTo(1);
        assertThat(countRefunds(reused.merchantId())).isEqualTo(1);
        assertThat(countRefunds(processing.merchantId())).isEqualTo(1);
    }

    @Test
    void shouldMapPaymentOwnershipStateAndCapacityFailuresToSafeProblems() throws Exception {
        StoredKey owner = createStoredKey("mrc_refund_owner", "key_refund_owner");
        StoredKey other = createStoredKey("mrc_refund_other", "key_refund_other");
        PaymentFixture owned = insertPayment(
                owner,
                "owned",
                "SUCCEEDED",
                1_000L,
                "VND"
        );
        PaymentFixture nonRefundable = insertPayment(
                other,
                "non_refundable",
                "CREATED",
                1_000L,
                "VND"
        );
        PaymentFixture limited = insertPayment(
                other,
                "limited",
                "SUCCEEDED",
                100L,
                "VND"
        );

        performCreate(
                other.rawKey(),
                owned.publicId(),
                "refund-cross-merchant",
                requestBody(100L, null)
        )
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYMENT_NOT_FOUND"));
        performCreate(
                other.rawKey(),
                nonRefundable.publicId(),
                "refund-invalid-state",
                requestBody(100L, null)
        )
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REFUND_INVALID_PAYMENT_STATE"));
        performCreate(
                other.rawKey(),
                limited.publicId(),
                "refund-exceeds",
                requestBody(101L, null)
        )
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REFUND_AMOUNT_EXCEEDS_AVAILABLE"));

        assertThat(refundProvider.invocationCount()).isZero();
        assertThat(countRefunds(owner.merchantId())).isZero();
        assertThat(countRefunds(other.merchantId())).isZero();
    }

    @Test
    void shouldRejectInvalidRevokedAndInactiveMerchantCredentials() throws Exception {
        mockMvc.perform(post(path("pi_refund_missing_auth"))
                        .header(RefundIdempotencyHeaders.KEY, "refund-missing-auth")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(100L, null)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));

        GeneratedApiKeySecret unknown = secretCodec.generate();
        performCreate(
                unknown.rawKey(),
                "pi_refund_unknown_auth",
                "refund-unknown-auth",
                requestBody(100L, null)
        )
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_API_KEY"))
                .andExpect(safeProblem(unknown.rawKey()));

        StoredKey revoked = createStoredKey("mrc_refund_revoked", "key_refund_revoked");
        PaymentFixture revokedPayment = insertPayment(
                revoked,
                "revoked",
                "SUCCEEDED",
                1_000L,
                "VND"
        );
        ApiKey apiKey = apiKeyRepository.findByPublicId(revoked.apiKeyPublicId())
                .orElseThrow();
        apiKey.revoke(Instant.now());
        apiKeyRepository.save(apiKey);
        performCreate(
                revoked.rawKey(),
                revokedPayment.publicId(),
                "refund-revoked",
                requestBody(100L, null)
        )
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("API_KEY_REVOKED"));

        StoredKey inactive = createStoredKey("mrc_refund_inactive", "key_refund_inactive");
        PaymentFixture inactivePayment = insertPayment(
                inactive,
                "inactive",
                "SUCCEEDED",
                1_000L,
                "VND"
        );
        jdbcTemplate.update(
                "UPDATE merchants SET status = 'SUSPENDED' WHERE id = ?",
                inactive.merchantId()
        );
        performCreate(
                inactive.rawKey(),
                inactivePayment.publicId(),
                "refund-inactive",
                requestBody(100L, null)
        )
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("MERCHANT_SUSPENDED"));

        assertThat(refundProvider.invocationCount()).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM refunds",
                Integer.class
        )).isZero();
    }

    private org.springframework.test.web.servlet.ResultActions performCreate(
            String rawApiKey,
            String paymentPublicId,
            String idempotencyKey,
            String body
    ) throws Exception {
        return mockMvc.perform(post(path(paymentPublicId))
                .header(HttpHeaders.AUTHORIZATION, bearer(rawApiKey))
                .header(RefundIdempotencyHeaders.KEY, idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private void assertOutcomeFields(
            MvcResult result,
            RefundProviderOutcome outcome,
            String refundId
    ) throws Exception {
        var body = objectMapper.readTree(result.getResponse().getContentAsByteArray())
                .path("data");
        RefundProviderResult providerResult = refundProvider.resultFor(outcome, refundId);
        assertThat(body.has("providerRefundId")).isTrue();
        assertThat(body.has("failureCode")).isTrue();
        assertThat(body.has("failureMessage")).isTrue();
        assertThat(body.has("completedAt")).isTrue();
        assertThat(body.path("providerRefundId").stringValue())
                .isEqualTo(providerResult.providerRefundId());
        assertThat(body.path("failureCode").stringValue())
                .isEqualTo(providerResult.failureCode());
        assertThat(body.path("failureMessage").stringValue())
                .isEqualTo(providerResult.failureMessage());
        if (outcome == RefundProviderOutcome.UNKNOWN) {
            assertThat(body.path("completedAt").isNull()).isTrue();
        } else {
            assertThat(body.path("completedAt").isString()).isTrue();
        }
        assertThat(body.propertyNames()).containsExactlyInAnyOrder(
                "id",
                "paymentId",
                "amount",
                "currency",
                "status",
                "reason",
                "provider",
                "providerRefundId",
                "failureCode",
                "failureMessage",
                "createdAt",
                "updatedAt",
                "completedAt"
        );
    }

    private StoredKey createStoredKey(String merchantPublicId, String apiKeyPublicId) {
        Merchant merchant = merchantRepository.save(Merchant.create(
                merchantPublicId,
                "Refund API Store",
                CREATED_AT
        ));
        GeneratedApiKeySecret secret = secretCodec.generate();
        ApiKey apiKey = apiKeyRepository.save(ApiKey.create(
                apiKeyPublicId,
                merchant.id(),
                "Refund API key",
                secret.prefix(),
                secret.digest(),
                null,
                CREATED_AT
        ));
        return new StoredKey(
                merchant.id(),
                merchant.publicId(),
                apiKey.publicId(),
                secret.rawKey()
        );
    }

    private PaymentFixture insertPayment(
            StoredKey stored,
            String suffix,
            String status,
            long amount,
            String currency
    ) {
        String paymentPublicId = "pi_refund_api_" + suffix;
        long paymentId = jdbcTemplate.queryForObject(
                """
                INSERT INTO payment_intents (
                    public_id, merchant_id, amount_minor, currency, status,
                    refunded_amount_minor, refund_reserved_minor,
                    created_at, updated_at, version
                )
                VALUES (?, ?, ?, ?, ?, 0, 0, ?, ?, 0)
                RETURNING id
                """,
                Long.class,
                paymentPublicId,
                stored.merchantId(),
                amount,
                currency,
                status,
                utc(CREATED_AT),
                utc(CREATED_AT.plusSeconds(2))
        );
        if (status.equals("SUCCEEDED")) {
            jdbcTemplate.update(
                    """
                    INSERT INTO payment_transactions (
                        public_id, payment_intent_id, attempt_no, provider,
                        provider_transaction_id, status, started_at, completed_at,
                        created_at, updated_at, version
                    )
                    VALUES (?, ?, 1, 'SIMULATOR', ?, 'SUCCEEDED', ?, ?, ?, ?, 0)
                    """,
                    "ptxn_refund_api_" + suffix,
                    paymentId,
                    "provider_charge_" + suffix,
                    utc(CREATED_AT.plusSeconds(1)),
                    utc(CREATED_AT.plusSeconds(2)),
                    utc(CREATED_AT.plusSeconds(1)),
                    utc(CREATED_AT.plusSeconds(2))
            );
        }
        return new PaymentFixture(paymentId, paymentPublicId);
    }

    private Map<String, Object> paymentCapacity(long paymentId) {
        return jdbcTemplate.queryForMap(
                """
                SELECT status, refunded_amount_minor, refund_reserved_minor
                FROM payment_intents
                WHERE id = ?
                """,
                paymentId
        );
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
                "SELECT count(*) FROM idempotency_records WHERE merchant_id = ?",
                Integer.class,
                merchantId
        );
    }

    private String responseId(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray())
                .path("data")
                .path("id")
                .stringValue();
    }

    private static String requestBody(long amount, String reason) {
        String reasonJson = reason == null
                ? "null"
                : "\"" + reason + "\"";
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

    private static RefundStatus refundStatus(RefundProviderOutcome outcome) {
        return switch (outcome) {
            case SUCCESS -> RefundStatus.SUCCEEDED;
            case DECLINED, TECHNICAL_FAILURE -> RefundStatus.FAILED;
            case UNKNOWN -> RefundStatus.PROCESSING;
        };
    }

    private static java.time.OffsetDateTime utc(Instant value) {
        return value.atOffset(ZoneOffset.UTC);
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
                        "DataIntegrityViolationException",
                        "org.hibernate",
                        "org.postgresql"
                );
    }

    private record StoredKey(
            long merchantId,
            String merchantPublicId,
            String apiKeyPublicId,
            String rawKey
    ) {
    }

    private record PaymentFixture(long internalId, String publicId) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProviderTestConfiguration {

        @Bean
        @Primary
        ControllableRefundProvider controllableRefundProvider() {
            return new ControllableRefundProvider();
        }
    }

    static final class ControllableRefundProvider implements RefundProviderPort {

        private final AtomicInteger invocationCount = new AtomicInteger();
        private volatile RefundProviderOutcome outcome = RefundProviderOutcome.SUCCESS;

        @Override
        public RefundProviderResult refund(RefundProviderRequest request) {
            invocationCount.incrementAndGet();
            return resultFor(outcome, request.refundPublicId());
        }

        RefundProviderResult resultFor(
                RefundProviderOutcome providerOutcome,
                String refundPublicId
        ) {
            return switch (providerOutcome) {
                case SUCCESS -> new RefundProviderResult(
                        "SIMULATOR",
                        providerOutcome,
                        "provider_" + refundPublicId,
                        null,
                        null
                );
                case DECLINED -> new RefundProviderResult(
                        "SIMULATOR",
                        providerOutcome,
                        null,
                        "REFUND_DECLINED",
                        "The provider declined the refund."
                );
                case TECHNICAL_FAILURE -> new RefundProviderResult(
                        "SIMULATOR",
                        providerOutcome,
                        null,
                        "PROVIDER_UNAVAILABLE",
                        "The Refund provider operation did not complete."
                );
                case UNKNOWN -> new RefundProviderResult(
                        "SIMULATOR",
                        providerOutcome,
                        null,
                        "PROVIDER_TIMEOUT",
                        "The Refund provider outcome is unknown."
                );
            };
        }

        void respondWith(RefundProviderOutcome providerOutcome) {
            outcome = providerOutcome;
        }

        int invocationCount() {
            return invocationCount.get();
        }

        void reset() {
            invocationCount.set(0);
            outcome = RefundProviderOutcome.SUCCESS;
        }
    }
}
