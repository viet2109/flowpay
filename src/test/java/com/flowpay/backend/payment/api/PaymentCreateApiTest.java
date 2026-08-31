package com.flowpay.backend.payment.api;

import com.flowpay.backend.idempotency.application.CreatePaymentFingerprint;
import com.flowpay.backend.idempotency.application.IdempotencyAcquisitionCommand;
import com.flowpay.backend.idempotency.application.IdempotencyAcquisitionDecision;
import com.flowpay.backend.idempotency.application.IdempotencyAcquisitionService;
import com.flowpay.backend.idempotency.application.RequestFingerprintService;
import com.flowpay.backend.idempotency.domain.IdempotencyKey;
import com.flowpay.backend.idempotency.domain.IdempotencyOperation;
import com.flowpay.backend.merchant.application.ApiKeyRepository;
import com.flowpay.backend.merchant.application.ApiKeySecretCodec;
import com.flowpay.backend.merchant.application.GeneratedApiKeySecret;
import com.flowpay.backend.merchant.application.MerchantRepository;
import com.flowpay.backend.merchant.domain.ApiKey;
import com.flowpay.backend.merchant.domain.Merchant;
import com.flowpay.backend.testing.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;

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
class PaymentCreateApiTest extends PostgresIntegrationTest {

    private static final String PATH = "/api/v1/payment-intents";
    private static final String IDEMPOTENCY_KEY = "checkout-create-api";
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
    private IdempotencyAcquisitionService acquisitionService;

    @Autowired
    private RequestFingerprintService fingerprintService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanData() {
        jdbcTemplate.update("""
                TRUNCATE TABLE idempotency_records, payment_transactions, payment_intents,
                    refresh_tokens, merchant_api_keys, merchant_members, merchants, users
                    RESTART IDENTITY CASCADE
                """);
    }

    @Test
    void shouldCreateAndReplayTheExactStoredPublicResponse() throws Exception {
        StoredKey stored = createStoredKey("mrc_create_api", "key_create_api");

        MvcResult created = performCreate(stored.rawKey(), IDEMPOTENCY_KEY, requestBody(50_000L))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string(HttpHeaders.LOCATION, containsString(PATH + "/pi_")))
                .andExpect(header().doesNotExist(PaymentCreationController.IDEMPOTENCY_REPLAYED_HEADER))
                .andExpect(jsonPath("$.data.id").isString())
                .andExpect(jsonPath("$.data.orderId").value("ORDER-1001"))
                .andExpect(jsonPath("$.data.description").value("Payment for ORDER-1001"))
                .andExpect(jsonPath("$.data.amount").value(50_000))
                .andExpect(jsonPath("$.data.currency").value("VND"))
                .andExpect(jsonPath("$.data.status").value("CREATED"))
                .andExpect(jsonPath("$.data.refundedAmount").value(0))
                .andExpect(jsonPath("$.data.refundableAmount").value(0))
                .andExpect(jsonPath("$.data.createdAt").isString())
                .andExpect(jsonPath("$.data.updatedAt").doesNotExist())
                .andExpect(jsonPath("$.meta").doesNotExist())
                .andExpect(content().string(not(containsString("internalId"))))
                .andExpect(content().string(not(containsString("merchantId"))))
                .andExpect(content().string(not(containsString("executionId"))))
                .andExpect(content().string(not(containsString("requestHash"))))
                .andExpect(content().string(not(containsString("responsePayload"))))
                .andExpect(content().string(not(containsString("\"version\""))))
                .andExpect(content().string(not(containsString(stored.rawKey()))))
                .andExpect(content().string(not(containsString(IDEMPOTENCY_KEY))))
                .andReturn();

        MvcResult replayed = performCreate(stored.rawKey(), IDEMPOTENCY_KEY, requestBody(50_000L))
                .andExpect(status().isCreated())
                .andExpect(header().string(
                        PaymentCreationController.IDEMPOTENCY_REPLAYED_HEADER,
                        "true"
                ))
                .andExpect(header().string(
                        HttpHeaders.LOCATION,
                        created.getResponse().getHeader(HttpHeaders.LOCATION)
                ))
                .andReturn();

        assertThat(replayed.getResponse().getContentAsString())
                .isEqualTo(created.getResponse().getContentAsString());
        assertThat(countRows("payment_intents", stored.merchantId())).isEqualTo(1);
        assertThat(countRows("idempotency_records", stored.merchantId())).isEqualTo(1);
    }

    @Test
    void shouldRejectMissingBlankAndOversizedIdempotencyKeys() throws Exception {
        StoredKey stored = createStoredKey("mrc_create_key_validation", "key_create_key_validation");

        mockMvc.perform(post(PATH)
                        .header(HttpHeaders.AUTHORIZATION, bearer(stored.rawKey()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(50_000L)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));

        performCreate(stored.rawKey(), "   ", requestBody(50_000L))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));

        String oversized = "k".repeat(256);
        performCreate(stored.rawKey(), oversized, requestBody(50_000L))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(content().string(not(containsString(oversized))));

        assertThat(countRows("payment_intents", stored.merchantId())).isZero();
        assertThat(countRows("idempotency_records", stored.merchantId())).isZero();
    }

    @Test
    void shouldValidateCreateRequestBeforePersistingAnything() throws Exception {
        StoredKey stored = createStoredKey("mrc_create_body_validation", "key_create_body_validation");

        performCreate(stored.rawKey(), "invalid-amount", requestBody(0L))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("amount"));

        performCreate(stored.rawKey(), "invalid-currency", """
                {
                  "amount": 50000,
                  "currency": "ZZZ",
                  "orderId": "ORDER-1001",
                  "description": "Payment for ORDER-1001"
                }
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("currency"));

        assertThat(countRows("payment_intents", stored.merchantId())).isZero();
        assertThat(countRows("idempotency_records", stored.merchantId())).isZero();
    }

    @Test
    void shouldRejectReusedKeyAndProcessingDuplicateWithoutCreatingAgain() throws Exception {
        StoredKey reused = createStoredKey("mrc_create_reused_api", "key_create_reused_api");
        performCreate(reused.rawKey(), "reused-key", requestBody(50_000L))
                .andExpect(status().isCreated());
        performCreate(reused.rawKey(), "reused-key", requestBody(50_001L))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"))
                .andExpect(jsonPath("$.type").value(
                        "https://flowpay.dev/problems/idempotency-key-reused"
                ));
        assertThat(countRows("payment_intents", reused.merchantId())).isEqualTo(1);

        StoredKey processing = createStoredKey(
                "mrc_create_processing_api",
                "key_create_processing_api"
        );
        IdempotencyKey processingKey = IdempotencyKey.of("processing-key");
        String requestHash = fingerprintService.fingerprint(CreatePaymentFingerprint.version1(
                50_000L,
                "VND",
                "ORDER-1001",
                "Payment for ORDER-1001"
        ));
        assertThat(acquisitionService.acquire(new IdempotencyAcquisitionCommand(
                processing.merchantId(),
                IdempotencyOperation.PAYMENT_INTENT_CREATE,
                processingKey,
                requestHash
        )).decision()).isEqualTo(IdempotencyAcquisitionDecision.NEW);

        performCreate(processing.rawKey(), processingKey.value(), requestBody(50_000L))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_REQUEST_IN_PROGRESS"));
        assertThat(countRows("payment_intents", processing.merchantId())).isZero();
    }

    @Test
    void shouldRejectInvalidAndRevokedApiKeysBeforeCreate() throws Exception {
        GeneratedApiKeySecret unknown = secretCodec.generate();
        performCreate(unknown.rawKey(), IDEMPOTENCY_KEY, requestBody(50_000L))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_API_KEY"))
                .andExpect(content().string(not(containsString(unknown.rawKey()))));

        StoredKey revoked = createStoredKey("mrc_create_revoked", "key_create_revoked");
        ApiKey apiKey = apiKeyRepository.findByPublicId(revoked.apiKeyPublicId()).orElseThrow();
        apiKey.revoke(Instant.now());
        apiKeyRepository.save(apiKey);

        performCreate(revoked.rawKey(), IDEMPOTENCY_KEY, requestBody(50_000L))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("API_KEY_REVOKED"))
                .andExpect(content().string(not(containsString(revoked.rawKey()))));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM payment_intents",
                Integer.class
        )).isZero();
    }

    @Test
    void sameTextualKeyShouldBeIndependentAcrossAuthenticatedMerchants() throws Exception {
        StoredKey first = createStoredKey("mrc_create_scope_api_first", "key_create_scope_first");
        StoredKey second = createStoredKey("mrc_create_scope_api_second", "key_create_scope_second");

        MvcResult firstResult = performCreate(
                first.rawKey(),
                "shared-create-key",
                requestBody(50_000L)
        ).andExpect(status().isCreated()).andReturn();
        MvcResult secondResult = performCreate(
                second.rawKey(),
                "shared-create-key",
                requestBody(50_000L)
        ).andExpect(status().isCreated()).andReturn();

        String firstId = responseId(firstResult);
        String secondId = responseId(secondResult);
        assertThat(firstId).isNotEqualTo(secondId);
        assertThat(countRows("payment_intents", first.merchantId())).isEqualTo(1);
        assertThat(countRows("payment_intents", second.merchantId())).isEqualTo(1);
        assertThat(countRows("idempotency_records", first.merchantId())).isEqualTo(1);
        assertThat(countRows("idempotency_records", second.merchantId())).isEqualTo(1);
    }

    private org.springframework.test.web.servlet.ResultActions performCreate(
            String rawApiKey,
            String idempotencyKey,
            String body
    ) throws Exception {
        return mockMvc.perform(post(PATH)
                .header(HttpHeaders.AUTHORIZATION, bearer(rawApiKey))
                .header(PaymentCreationController.IDEMPOTENCY_KEY_HEADER, idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private String responseId(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray())
                .path("data")
                .path("id")
                .stringValue();
    }

    private int countRows(String table, long merchantId) {
        if (!table.equals("payment_intents") && !table.equals("idempotency_records")) {
            throw new IllegalArgumentException("Unsupported table");
        }
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE merchant_id = ?",
                Integer.class,
                merchantId
        );
    }

    private StoredKey createStoredKey(String merchantPublicId, String apiKeyPublicId) {
        Merchant merchant = merchantRepository.save(Merchant.create(
                merchantPublicId,
                "Payment Create Store",
                CREATED_AT
        ));
        GeneratedApiKeySecret secret = secretCodec.generate();
        ApiKey apiKey = apiKeyRepository.save(ApiKey.create(
                apiKeyPublicId,
                merchant.id(),
                "Payment create key",
                secret.prefix(),
                secret.digest(),
                null,
                CREATED_AT
        ));
        return new StoredKey(merchant.id(), apiKey.publicId(), secret.rawKey());
    }

    private static String requestBody(long amount) {
        return """
                {
                  "amount": %d,
                  "currency": "VND",
                  "orderId": "ORDER-1001",
                  "description": "Payment for ORDER-1001"
                }
                """.formatted(amount);
    }

    private static String bearer(String rawKey) {
        return "Bearer " + rawKey;
    }

    private record StoredKey(long merchantId, String apiKeyPublicId, String rawKey) {
    }
}
