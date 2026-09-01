package com.flowpay.backend.refund.api;

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
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RefundQueryApiTest extends PostgresIntegrationTest {

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

    @BeforeEach
    void cleanData() {
        jdbcTemplate.update("""
                TRUNCATE TABLE refunds, idempotency_records, payment_transactions,
                    payment_intents, refresh_tokens, merchant_api_keys, merchant_members,
                    merchants, users RESTART IDENTITY CASCADE
                """);
    }

    @Test
    void shouldRetrieveOwnedRefundWithExactSafeResponseShape() throws Exception {
        StoredKey stored = createStoredKey("mrc_refund_get", "key_refund_get");
        PaymentFixture payment = insertPayment(
                stored,
                "get",
                "PARTIALLY_REFUNDED",
                1_000L,
                300L,
                0L
        );
        insertRefund(
                "re_refund_get",
                stored,
                payment,
                300L,
                "SUCCEEDED",
                "CUSTOMER_REQUEST",
                "provider_refund_get",
                null,
                null,
                CREATED_AT.plusSeconds(10)
        );

        MvcResult result = performGet(stored.rawKey(), "re_refund_get")
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data.id").value("re_refund_get"))
                .andExpect(jsonPath("$.data.paymentId").value(payment.publicId()))
                .andExpect(jsonPath("$.data.amount").value(300L))
                .andExpect(jsonPath("$.data.currency").value("USD"))
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.reason").value("CUSTOMER_REQUEST"))
                .andExpect(jsonPath("$.data.provider").value("SIMULATOR"))
                .andExpect(jsonPath("$.data.providerRefundId").value("provider_refund_get"))
                .andExpect(jsonPath("$.data.failureCode").value(nullValue()))
                .andExpect(jsonPath("$.data.failureMessage").value(nullValue()))
                .andExpect(jsonPath("$.data.createdAt").isString())
                .andExpect(jsonPath("$.data.updatedAt").isString())
                .andExpect(jsonPath("$.data.completedAt").isString())
                .andExpect(jsonPath("$.meta").doesNotExist())
                .andExpect(content().string(not(containsString("internalId"))))
                .andExpect(content().string(not(containsString("merchantId"))))
                .andExpect(content().string(not(containsString("paymentIntentId"))))
                .andExpect(content().string(not(containsString("requestHash"))))
                .andExpect(content().string(not(containsString("responsePayload"))))
                .andExpect(content().string(not(containsString("\"version\""))))
                .andReturn();

        assertExactRefundFields(result, false);
    }

    @Test
    void shouldHideCrossMerchantAndUnknownRefundsAsNotFound() throws Exception {
        StoredKey owner = createStoredKey("mrc_refund_get_owner", "key_refund_get_owner");
        StoredKey other = createStoredKey("mrc_refund_get_other", "key_refund_get_other");
        PaymentFixture payment = insertPayment(
                owner,
                "get_owned",
                "SUCCEEDED",
                1_000L,
                0L,
                0L
        );
        insertRefund(
                "re_refund_owned",
                owner,
                payment,
                100L,
                "FAILED",
                null,
                null,
                "REFUND_DECLINED",
                "The provider declined the refund.",
                CREATED_AT.plusSeconds(5)
        );

        performGet(other.rawKey(), "re_refund_owned")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REFUND_NOT_FOUND"));
        performGet(owner.rawKey(), "re_refund_missing")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REFUND_NOT_FOUND"));
    }

    @Test
    void shouldListCurrentStatesNewestFirstWithPagination() throws Exception {
        StoredKey stored = createStoredKey("mrc_refund_list", "key_refund_list");
        PaymentFixture payment = insertPayment(
                stored,
                "list",
                "PARTIALLY_REFUNDED",
                1_000L,
                200L,
                150L
        );
        insertRefund(
                "re_refund_succeeded",
                stored,
                payment,
                200L,
                "SUCCEEDED",
                "CUSTOMER_REQUEST",
                "provider_refund_succeeded",
                null,
                null,
                CREATED_AT.plusSeconds(1)
        );
        insertRefund(
                "re_refund_failed",
                stored,
                payment,
                100L,
                "FAILED",
                "DUPLICATE",
                null,
                "REFUND_DECLINED",
                "The provider declined the refund.",
                CREATED_AT.plusSeconds(2)
        );
        insertRefund(
                "re_refund_processing",
                stored,
                payment,
                150L,
                "PROCESSING",
                null,
                null,
                "PROVIDER_TIMEOUT",
                "The Refund provider outcome is unknown.",
                CREATED_AT.plusSeconds(3)
        );

        MvcResult firstPage = performList(stored.rawKey(), payment.publicId(), "?page=0&size=2")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].id").value("re_refund_processing"))
                .andExpect(jsonPath("$.data[0].status").value("PROCESSING"))
                .andExpect(jsonPath("$.data[0].completedAt").value(nullValue()))
                .andExpect(jsonPath("$.data[1].id").value("re_refund_failed"))
                .andExpect(jsonPath("$.data[1].status").value("FAILED"))
                .andExpect(jsonPath("$.data[1].failureCode").value("REFUND_DECLINED"))
                .andExpect(jsonPath("$.meta.page").value(0))
                .andExpect(jsonPath("$.meta.size").value(2))
                .andExpect(jsonPath("$.meta.totalElements").value(3))
                .andExpect(jsonPath("$.meta.totalPages").value(2))
                .andExpect(jsonPath("$.meta.hasNext").value(true))
                .andExpect(jsonPath("$.meta.hasPrevious").value(false))
                .andReturn();
        assertExactRefundFields(firstPage, true);

        performList(stored.rawKey(), payment.publicId(), "?page=1&size=2")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value("re_refund_succeeded"))
                .andExpect(jsonPath("$.data[0].status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.meta.hasNext").value(false))
                .andExpect(jsonPath("$.meta.hasPrevious").value(true));
    }

    @Test
    void shouldUsePaginationDefaultsAndReturnEmptyForOwnedPayment() throws Exception {
        StoredKey stored = createStoredKey("mrc_refund_empty", "key_refund_empty");
        PaymentFixture payment = insertPayment(
                stored,
                "empty",
                "SUCCEEDED",
                1_000L,
                0L,
                0L
        );

        performList(stored.rawKey(), payment.publicId(), "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0))
                .andExpect(jsonPath("$.meta.page").value(0))
                .andExpect(jsonPath("$.meta.size").value(20))
                .andExpect(jsonPath("$.meta.totalElements").value(0))
                .andExpect(jsonPath("$.meta.totalPages").value(0))
                .andExpect(jsonPath("$.meta.hasNext").value(false))
                .andExpect(jsonPath("$.meta.hasPrevious").value(false));
        performList(stored.rawKey(), payment.publicId(), "?size=100")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.size").value(100));
    }

    @Test
    void shouldRejectInvalidPaginationBeforeRepositoryQuery() throws Exception {
        StoredKey stored = createStoredKey("mrc_refund_page", "key_refund_page");
        PaymentFixture payment = insertPayment(
                stored,
                "page",
                "SUCCEEDED",
                1_000L,
                0L,
                0L
        );

        performList(stored.rawKey(), payment.publicId(), "?page=-1")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        performList(stored.rawKey(), payment.publicId(), "?size=0")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        performList(stored.rawKey(), payment.publicId(), "?size=101")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void shouldHideCrossMerchantPaymentAndRequireApiKeyAuthentication() throws Exception {
        StoredKey owner = createStoredKey("mrc_refund_list_owner", "key_refund_list_owner");
        StoredKey other = createStoredKey("mrc_refund_list_other", "key_refund_list_other");
        PaymentFixture payment = insertPayment(
                owner,
                "list_owned",
                "SUCCEEDED",
                1_000L,
                0L,
                0L
        );

        performList(other.rawKey(), payment.publicId(), "")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYMENT_NOT_FOUND"));
        mockMvc.perform(get("/api/v1/refunds/re_refund_auth"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        mockMvc.perform(get(listPath(payment.publicId())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    private org.springframework.test.web.servlet.ResultActions performGet(
            String rawApiKey,
            String refundPublicId
    ) throws Exception {
        return mockMvc.perform(get("/api/v1/refunds/" + refundPublicId)
                .header(HttpHeaders.AUTHORIZATION, bearer(rawApiKey)));
    }

    private org.springframework.test.web.servlet.ResultActions performList(
            String rawApiKey,
            String paymentPublicId,
            String query
    ) throws Exception {
        return mockMvc.perform(get(listPath(paymentPublicId) + query)
                .header(HttpHeaders.AUTHORIZATION, bearer(rawApiKey)));
    }

    private StoredKey createStoredKey(String merchantPublicId, String apiKeyPublicId) {
        Merchant merchant = merchantRepository.save(Merchant.create(
                merchantPublicId,
                "Refund Query Store",
                CREATED_AT
        ));
        GeneratedApiKeySecret secret = secretCodec.generate();
        ApiKey apiKey = apiKeyRepository.save(ApiKey.create(
                apiKeyPublicId,
                merchant.id(),
                "Refund query key",
                secret.prefix(),
                secret.digest(),
                null,
                CREATED_AT
        ));
        return new StoredKey(merchant.id(), secret.rawKey());
    }

    private PaymentFixture insertPayment(
            StoredKey stored,
            String suffix,
            String status,
            long amount,
            long refunded,
            long reserved
    ) {
        String publicId = "pi_refund_query_" + suffix;
        long internalId = jdbcTemplate.queryForObject(
                """
                INSERT INTO payment_intents (
                    public_id, merchant_id, amount_minor, currency, status,
                    refunded_amount_minor, refund_reserved_minor,
                    created_at, updated_at, version
                )
                VALUES (?, ?, ?, 'USD', ?, ?, ?, ?, ?, 0)
                RETURNING id
                """,
                Long.class,
                publicId,
                stored.merchantId(),
                amount,
                status,
                refunded,
                reserved,
                utc(CREATED_AT),
                utc(CREATED_AT.plusSeconds(10))
        );
        return new PaymentFixture(internalId, publicId);
    }

    private void insertRefund(
            String publicId,
            StoredKey stored,
            PaymentFixture payment,
            long amount,
            String status,
            String reason,
            String providerRefundId,
            String failureCode,
            String failureMessage,
            Instant createdAt
    ) {
        boolean terminal = status.equals("SUCCEEDED") || status.equals("FAILED");
        Instant updatedAt = createdAt.plusSeconds(1);
        jdbcTemplate.update(
                """
                INSERT INTO refunds (
                    public_id, merchant_id, payment_intent_id, amount_minor, currency,
                    status, reason, provider, provider_refund_id, failure_code,
                    failure_message, created_at, updated_at, completed_at, version
                )
                VALUES (?, ?, ?, ?, 'USD', ?, ?, 'SIMULATOR', ?, ?, ?, ?, ?, ?, 0)
                """,
                publicId,
                stored.merchantId(),
                payment.internalId(),
                amount,
                status,
                reason,
                providerRefundId,
                failureCode,
                failureMessage,
                utc(createdAt),
                utc(updatedAt),
                terminal ? utc(updatedAt) : null
        );
    }

    private void assertExactRefundFields(MvcResult result, boolean paged) throws Exception {
        var root = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        var refund = paged ? root.path("data").get(0) : root.path("data");
        assertThat(refund.propertyNames()).containsExactlyInAnyOrder(
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

    private static String listPath(String paymentPublicId) {
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
}
