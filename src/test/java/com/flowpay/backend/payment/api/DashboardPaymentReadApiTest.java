package com.flowpay.backend.payment.api;

import com.flowpay.backend.merchant.application.ApiKeyManagementUseCase;
import com.flowpay.backend.merchant.application.CreateApiKeyCommand;
import com.flowpay.backend.merchant.domain.MerchantRole;
import com.flowpay.backend.testing.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Dashboard reads (JWT) over data created through the Merchant API (API key):
 * same bodies as the Merchant API, every role, tenant isolation, auth and
 * validation failures.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DashboardPaymentReadApiTest extends PostgresIntegrationTest {

    private static final String OWNER = "mrc_dashboard_reads";
    private static final String OTHER = "mrc_dashboard_other";

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtEncoder jwtEncoder;
    @Autowired private ObjectMapper json;
    @Autowired private ApiKeyManagementUseCase apiKeys;

    private String ownerKey;
    private String ownerToken;
    private String refundedPayment;
    private String createdPayment;
    private String refundId;
    private String otherPayment;
    private String otherRefund;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.execute("""
                TRUNCATE TABLE ledger_entries, ledger_transactions, ledger_accounts, outbox_events,
                    refunds, idempotency_records, payment_transactions, payment_intents,
                    merchant_api_keys, merchant_members, merchants, users RESTART IDENTITY CASCADE
                """);
        merchant(OWNER);
        merchant(OTHER);
        ownerKey = apiKeys.create(new CreateApiKeyCommand(OWNER, "Dashboard read test")).rawKey();
        String otherKey = apiKeys.create(new CreateApiKeyCommand(OTHER, "Dashboard read test")).rawKey();
        ownerToken = token(OWNER, MerchantRole.OWNER);

        refundedPayment = createPayment(ownerKey, 25_000, "ORD-READ-1");
        confirm(ownerKey, refundedPayment);
        refundId = refund(ownerKey, refundedPayment, 5_000);
        createdPayment = createPayment(ownerKey, 9_900, "ORD-READ-2");
        // The other merchant reuses an order ID: filters must still stay inside one merchant.
        otherPayment = createPayment(otherKey, 12_000, "ORD-READ-1");
        confirm(otherKey, otherPayment);
        otherRefund = refund(otherKey, otherPayment, 1_000);
    }

    @Test
    void everyReadReturnsExactlyWhatTheMerchantApiReturnsForTheSameMerchant() throws Exception {
        Map<String, String> dashboardToMerchantApi = Map.of(
                "/api/v1/merchant/payments", "/api/v1/payment-intents",
                "/api/v1/merchant/payments?status=PARTIALLY_REFUNDED", "/api/v1/payment-intents?status=PARTIALLY_REFUNDED",
                "/api/v1/merchant/payments?orderId=ORD-READ-1&size=1", "/api/v1/payment-intents?orderId=ORD-READ-1&size=1",
                "/api/v1/merchant/payments/" + refundedPayment, "/api/v1/payment-intents/" + refundedPayment,
                "/api/v1/merchant/payments/" + refundedPayment + "/transactions",
                "/api/v1/payment-intents/" + refundedPayment + "/transactions",
                "/api/v1/merchant/payments/" + refundedPayment + "/refunds",
                "/api/v1/payment-intents/" + refundedPayment + "/refunds",
                "/api/v1/merchant/refunds/" + refundId, "/api/v1/refunds/" + refundId
        );
        for (var route : dashboardToMerchantApi.entrySet()) {
            JsonNode dashboard = body(mvc.perform(get(route.getKey()).header(HttpHeaders.AUTHORIZATION, ownerToken))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            JsonNode merchantApi = body(mvc.perform(get(route.getValue()).header(HttpHeaders.AUTHORIZATION, bearer(ownerKey)))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            assertThat(dashboard).as(route.getKey()).isEqualTo(merchantApi);
        }
    }

    @Test
    void listsAreScopedToTheTokenMerchantIncludingFilters() throws Exception {
        var all = body(mvc.perform(get("/api/v1/merchant/payments").header(HttpHeaders.AUTHORIZATION, ownerToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.meta.totalElements").value(2))
                .andReturn().getResponse().getContentAsString());
        assertThat(ids(all.path("data"))).containsExactlyInAnyOrder(refundedPayment, createdPayment);

        mvc.perform(get("/api/v1/merchant/payments").param("orderId", "ORD-READ-1")
                        .header(HttpHeaders.AUTHORIZATION, ownerToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.meta.totalElements").value(1))
                .andExpect(jsonPath("$.data[0].id").value(refundedPayment));
        mvc.perform(get("/api/v1/merchant/payments").param("orderId", "ORD-READ-1")
                        .header(HttpHeaders.AUTHORIZATION, token(OTHER, MerchantRole.OWNER)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.meta.totalElements").value(1))
                .andExpect(jsonPath("$.data[0].id").value(otherPayment));
    }

    @Test
    void anotherMerchantsRecordsAreNotFoundAndIndistinguishableFromMissingOnes() throws Exception {
        Map<String, String> notFound = Map.of(
                "/api/v1/merchant/payments/" + otherPayment, "PAYMENT_NOT_FOUND",
                "/api/v1/merchant/payments/" + otherPayment + "/transactions", "PAYMENT_NOT_FOUND",
                "/api/v1/merchant/payments/" + otherPayment + "/refunds", "PAYMENT_NOT_FOUND",
                "/api/v1/merchant/refunds/" + otherRefund, "REFUND_NOT_FOUND",
                "/api/v1/merchant/payments/pi_does_not_exist", "PAYMENT_NOT_FOUND",
                "/api/v1/merchant/refunds/re_does_not_exist", "REFUND_NOT_FOUND"
        );
        for (var route : notFound.entrySet()) {
            String body = mvc.perform(get(route.getKey()).header(HttpHeaders.AUTHORIZATION, ownerToken))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(route.getValue()))
                    .andReturn().getResponse().getContentAsString();
            assertThat(body).as(route.getKey()).doesNotContain(OTHER, "merchant_id");
        }
    }

    @Test
    void everyDashboardRoleCanRead() throws Exception {
        for (MerchantRole role : MerchantRole.values()) {
            String token = token(OWNER, role);
            for (String path : readPaths()) {
                mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, token))
                        .andExpect(status().isOk());
            }
        }
    }

    @Test
    void requiresADashboardTokenAndRejectsMerchantApiKeys() throws Exception {
        for (String authorization : List.of("", "Bearer not-a-token", bearer(ownerKey))) {
            for (String path : readPaths()) {
                MockHttpServletRequestBuilder request = get(path);
                if (!authorization.isEmpty()) {
                    request.header(HttpHeaders.AUTHORIZATION, authorization);
                }
                mvc.perform(request).andExpect(status().isUnauthorized())
                        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
            }
        }
        // And the other way round: the Merchant API still accepts only API keys.
        mvc.perform(get("/api/v1/payment-intents/" + refundedPayment).header(HttpHeaders.AUTHORIZATION, ownerToken))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_API_KEY"));
    }

    @Test
    void suspendedMerchantCannotRead() throws Exception {
        jdbc.update("UPDATE merchants SET status = 'SUSPENDED' WHERE public_id = ?", OWNER);
        for (String path : readPaths()) {
            mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, ownerToken))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MERCHANT_SUSPENDED"));
        }
    }

    @Test
    void invalidFiltersAndPagingAreRejectedLikeTheMerchantApi() throws Exception {
        List<String> invalid = List.of(
                "/api/v1/merchant/payments?size=101",
                "/api/v1/merchant/payments?size=0",
                "/api/v1/merchant/payments?page=-1",
                "/api/v1/merchant/payments?status=SETTLED",
                "/api/v1/merchant/payments?createdFrom=2026-11-02T00:00:00Z&createdTo=2026-11-01T00:00:00Z",
                "/api/v1/merchant/payments?createdFrom=yesterday",
                "/api/v1/merchant/payments/" + refundedPayment + "/refunds?size=101"
        );
        for (String path : invalid) {
            mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, ownerToken))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
    }

    private List<String> readPaths() {
        return List.of(
                "/api/v1/merchant/payments",
                "/api/v1/merchant/payments/" + refundedPayment,
                "/api/v1/merchant/payments/" + refundedPayment + "/transactions",
                "/api/v1/merchant/payments/" + refundedPayment + "/refunds",
                "/api/v1/merchant/refunds/" + refundId
        );
    }

    private String createPayment(String key, long amount, String orderId) throws Exception {
        return id(mvc.perform(post("/api/v1/payment-intents").header(HttpHeaders.AUTHORIZATION, bearer(key))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount": %d, "currency": "USD", "orderId": "%s", "description": "Dashboard read"}
                                """.formatted(amount, orderId)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private void confirm(String key, String paymentId) throws Exception {
        mvc.perform(post("/api/v1/payment-intents/" + paymentId + "/confirm").header(HttpHeaders.AUTHORIZATION, bearer(key))
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.paymentStatus").value("SUCCEEDED"));
    }

    private String refund(String key, String paymentId, long amount) throws Exception {
        return id(mvc.perform(post("/api/v1/payment-intents/" + paymentId + "/refunds")
                        .header(HttpHeaders.AUTHORIZATION, bearer(key))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount": %d, "reason": "Customer request"}
                                """.formatted(amount)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private void merchant(String publicId) {
        jdbc.update("""
                INSERT INTO merchants (public_id, name, status, created_at, updated_at)
                VALUES (?, 'Dashboard read merchant', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, publicId);
    }

    private String token(String merchantPublicId, MerchantRole role) {
        Instant now = Instant.now();
        return "Bearer " + jwtEncoder.encode(JwtEncoderParameters.from(JwtClaimsSet.builder()
                .issuer("https://flowpay.dev").issuedAt(now).expiresAt(now.plusSeconds(900))
                .subject("usr_dashboard_reader").claim("merchant", merchantPublicId).claim("role", role.name())
                .build())).getTokenValue();
    }

    private JsonNode body(String content) {
        return json.readTree(content);
    }

    private String id(String content) {
        return body(content).path("data").path("id").stringValue();
    }

    private static List<String> ids(JsonNode data) {
        return data.valueStream().map(node -> node.path("id").stringValue()).toList();
    }

    private static String bearer(String rawKey) {
        return "Bearer " + rawKey;
    }
}
