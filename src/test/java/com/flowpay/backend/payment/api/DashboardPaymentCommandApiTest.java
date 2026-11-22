package com.flowpay.backend.payment.api;

import com.flowpay.backend.merchant.application.ApiKeyManagementUseCase;
import com.flowpay.backend.merchant.application.CreateApiKeyCommand;
import com.flowpay.backend.merchant.domain.MerchantRole;
import com.flowpay.backend.payment.application.PaymentProviderPort;
import com.flowpay.backend.payment.application.PaymentProviderResult;
import com.flowpay.backend.payment.domain.ProviderOutcome;
import com.flowpay.backend.refund.application.RefundProviderPort;
import com.flowpay.backend.refund.application.RefundProviderResult;
import com.flowpay.backend.refund.domain.RefundProviderOutcome;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Dashboard commands (JWT, OWNER only): create, confirm, refund. They share use
 * cases and the idempotency scope with the Merchant API, so a key means one
 * logical request whichever entry point receives it, including concurrently.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DashboardPaymentCommandApiTest extends PostgresIntegrationTest {

    private static final String OWNER = "mrc_dashboard_commands";
    private static final String OTHER = "mrc_dashboard_commands_other";
    private static final String PAYMENTS = "/api/v1/merchant/payments";
    private static final String CREATE_BODY = """
            {"amount": 25000, "currency": "USD", "orderId": "ORD-CMD-1", "description": "Dashboard command"}
            """;

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtEncoder jwtEncoder;
    @Autowired private ObjectMapper json;
    @Autowired private ApiKeyManagementUseCase apiKeys;
    @MockitoBean private PaymentProviderPort paymentProvider;
    @MockitoBean private RefundProviderPort refundProvider;

    private final AtomicInteger charges = new AtomicInteger();
    private final AtomicInteger refunds = new AtomicInteger();
    private volatile ProviderOutcome chargeOutcome = ProviderOutcome.SUCCESS;
    /** When set, the provider call signals {@code entered} and waits for {@code release}. */
    private volatile CountDownLatch entered;
    private volatile CountDownLatch release;

    private String ownerToken;
    private String ownerKey;

    @BeforeEach
    void setUp() {
        jdbc.execute("""
                TRUNCATE TABLE ledger_entries, ledger_transactions, ledger_accounts, outbox_events,
                    refunds, idempotency_records, payment_transactions, payment_intents,
                    merchant_api_keys, merchant_members, merchants, users RESTART IDENTITY CASCADE
                """);
        merchant(OWNER);
        merchant(OTHER);
        ownerKey = apiKeys.create(new CreateApiKeyCommand(OWNER, "Dashboard command test")).rawKey();
        ownerToken = token(OWNER, MerchantRole.OWNER);
        when(paymentProvider.charge(any())).thenAnswer(call -> {
            charges.incrementAndGet();
            pauseIfGated();
            return switch (chargeOutcome) {
                case SUCCESS -> new PaymentProviderResult("SIMULATOR", ProviderOutcome.SUCCESS, "sim_charge_" + charges.get(), null, null);
                case DECLINED -> new PaymentProviderResult("SIMULATOR", ProviderOutcome.DECLINED, null, "CARD_DECLINED", "The card was declined.");
                case UNKNOWN -> new PaymentProviderResult("SIMULATOR", ProviderOutcome.UNKNOWN, null, "PROVIDER_TIMEOUT", "The provider did not answer.");
                case TECHNICAL_FAILURE -> new PaymentProviderResult("SIMULATOR", ProviderOutcome.TECHNICAL_FAILURE, null, "PROVIDER_ERROR", "The provider failed.");
            };
        });
        when(refundProvider.refund(any())).thenAnswer(call -> {
            refunds.incrementAndGet();
            pauseIfGated();
            return new RefundProviderResult("SIMULATOR", RefundProviderOutcome.SUCCESS, "sim_refund_" + refunds.get(), null, null);
        });
    }

    @Test
    void ownerCreatesConfirmsAndRefundsWithDashboardLocations() throws Exception {
        MvcResult created = dashboard(post(PAYMENTS), ownerToken, key(), CREATE_BODY)
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotency-Replayed"))
                .andExpect(jsonPath("$.data.status").value("CREATED"))
                .andExpect(jsonPath("$.data.amount").value(25000))
                .andReturn();
        String paymentId = id(created);
        assertThat(created.getResponse().getHeader(HttpHeaders.LOCATION)).isEqualTo(PAYMENTS + "/" + paymentId);

        dashboard(post(PAYMENTS + "/" + paymentId + "/confirm"), ownerToken, key(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.paymentStatus").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.transactionStatus").value("SUCCEEDED"));

        MvcResult refunded = dashboard(post(PAYMENTS + "/" + paymentId + "/refunds"), ownerToken, key(),
                "{\"amount\": 5000, \"reason\": \"Customer request\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.paymentId").value(paymentId))
                .andReturn();
        assertThat(refunded.getResponse().getHeader(HttpHeaders.LOCATION))
                .isEqualTo("/api/v1/merchant/refunds/" + id(refunded));

        // The Merchant API sees the same payment, with the refund applied.
        mvc.perform(get("/api/v1/payment-intents/" + paymentId).header(HttpHeaders.AUTHORIZATION, bearer(ownerKey)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PARTIALLY_REFUNDED"))
                .andExpect(jsonPath("$.data.refundedAmount").value(5000))
                .andExpect(jsonPath("$.data.refundableAmount").value(20000));
    }

    @Test
    void confirmReturnsTheSameStatusesAsTheMerchantApiForEachProviderOutcome() throws Exception {
        // A technical failure fails the attempt like a decline (FinalizePaymentConfirmationService);
        // only an unknown outcome leaves the payment PROCESSING.
        Map<ProviderOutcome, Integer> expected = Map.of(
                ProviderOutcome.SUCCESS, 200, ProviderOutcome.DECLINED, 200,
                ProviderOutcome.UNKNOWN, 202, ProviderOutcome.TECHNICAL_FAILURE, 200);
        for (var outcome : expected.entrySet()) {
            chargeOutcome = outcome.getKey();
            String viaDashboard = createViaDashboard();
            String viaMerchantApi = createViaMerchantApi(key());
            MvcResult dashboard = dashboard(post(PAYMENTS + "/" + viaDashboard + "/confirm"), ownerToken, key(), null)
                    .andExpect(status().is(outcome.getValue())).andReturn();
            MvcResult merchantApi = merchantApi(post("/api/v1/payment-intents/" + viaMerchantApi + "/confirm"), key(), null)
                    .andExpect(status().is(outcome.getValue())).andReturn();
            assertThat(data(dashboard).path("paymentStatus")).as(outcome.getKey().name())
                    .isEqualTo(data(merchantApi).path("paymentStatus"));
            assertThat(data(dashboard).path("transactionStatus")).isEqualTo(data(merchantApi).path("transactionStatus"));
        }
    }

    @Test
    void everyRoleButOwnerIsRefusedBeforeAKeyIsReservedOrAnythingIsWritten() throws Exception {
        String payment = createViaMerchantApi(key());
        merchantApi(post("/api/v1/payment-intents/" + payment + "/confirm"), key(), null).andExpect(status().isOk());
        Map<String, Long> before = counts();
        int chargesBefore = charges.get();
        String sharedKey = key();

        for (MerchantRole role : MerchantRole.values()) {
            if (role == MerchantRole.OWNER) {
                continue;
            }
            String token = token(OWNER, role);
            for (var request : List.of(
                    dashboard(post(PAYMENTS), token, sharedKey, CREATE_BODY),
                    dashboard(post(PAYMENTS + "/" + payment + "/confirm"), token, sharedKey, null),
                    dashboard(post(PAYMENTS + "/" + payment + "/refunds"), token, sharedKey, "{\"amount\": 100}"))) {
                request.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
            }
        }
        assertThat(counts()).isEqualTo(before);
        assertThat(charges.get()).isEqualTo(chargesBefore);
        assertThat(refunds.get()).isZero();

        // The refused key was never reserved: the owner's first use of it is a new request.
        dashboard(post(PAYMENTS), ownerToken, sharedKey, CREATE_BODY)
                .andExpect(status().isCreated()).andExpect(header().doesNotExist("Idempotency-Replayed"));
    }

    @Test
    void aKeyReplaysAcrossEntryPointsAndCannotBeReusedForAnotherRequest() throws Exception {
        // Create: Merchant API first, then the dashboard with the same key and body.
        String createKey = key();
        String paymentId = id(merchantApi(post("/api/v1/payment-intents"), createKey, CREATE_BODY)
                .andExpect(status().isCreated()).andReturn());
        MvcResult replayed = dashboard(post(PAYMENTS), ownerToken, createKey, CREATE_BODY)
                .andExpect(status().isCreated()).andExpect(header().string("Idempotency-Replayed", "true")).andReturn();
        assertThat(id(replayed)).isEqualTo(paymentId);
        dashboard(post(PAYMENTS), ownerToken, createKey, CREATE_BODY.replace("25000", "26000"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(count("payment_intents")).isEqualTo(1);

        // Confirm: dashboard first, then the Merchant API with the same key.
        String confirmKey = key();
        dashboard(post(PAYMENTS + "/" + paymentId + "/confirm"), ownerToken, confirmKey, null)
                .andExpect(status().isOk()).andExpect(header().doesNotExist("Idempotency-Replayed"));
        merchantApi(post("/api/v1/payment-intents/" + paymentId + "/confirm"), confirmKey, null)
                .andExpect(status().isOk()).andExpect(header().string("Idempotency-Replayed", "true"));
        assertThat(charges.get()).isEqualTo(1);

        // Refund: Merchant API first, then the dashboard; another amount with that key is refused.
        String refundKey = key();
        String refundBody = "{\"amount\": 4000, \"reason\": \"Customer request\"}";
        String refundId = id(merchantApi(post("/api/v1/payment-intents/" + paymentId + "/refunds"), refundKey, refundBody)
                .andExpect(status().isCreated()).andReturn());
        MvcResult refundReplay = dashboard(post(PAYMENTS + "/" + paymentId + "/refunds"), ownerToken, refundKey, refundBody)
                .andExpect(status().isCreated()).andExpect(header().string("Idempotency-Replayed", "true")).andReturn();
        assertThat(id(refundReplay)).isEqualTo(refundId);
        dashboard(post(PAYMENTS + "/" + paymentId + "/refunds"), ownerToken, refundKey, refundBody.replace("4000", "4001"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(count("refunds")).isEqualTo(1);
        assertThat(refunds.get()).isEqualTo(1);
    }

    @Test
    void aConfirmInFlightOnTheDashboardBlocksTheSameKeyOnTheMerchantApiThenReplays() throws Exception {
        String paymentId = createViaDashboard();
        String confirmKey = key();
        gate();
        CompletableFuture<MvcResult> first = CompletableFuture.supplyAsync(() -> perform(
                dashboardRequest(post(PAYMENTS + "/" + paymentId + "/confirm"), ownerToken, confirmKey, null)));
        assertThat(entered.await(10, TimeUnit.SECONDS)).as("provider reached").isTrue();

        merchantApi(post("/api/v1/payment-intents/" + paymentId + "/confirm"), confirmKey, null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_REQUEST_IN_PROGRESS"));

        release.countDown();
        assertThat(first.get(10, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
        merchantApi(post("/api/v1/payment-intents/" + paymentId + "/confirm"), confirmKey, null)
                .andExpect(status().isOk()).andExpect(header().string("Idempotency-Replayed", "true"));
        assertThat(charges.get()).isEqualTo(1);
        assertThat(count("payment_transactions")).isEqualTo(1);
    }

    @Test
    void aRefundInFlightOnTheMerchantApiBlocksTheSameKeyOnTheDashboardThenReplays() throws Exception {
        String paymentId = createViaDashboard();
        dashboard(post(PAYMENTS + "/" + paymentId + "/confirm"), ownerToken, key(), null).andExpect(status().isOk());
        String refundKey = key();
        String body = "{\"amount\": 2500, \"reason\": \"Customer request\"}";
        gate();
        CompletableFuture<MvcResult> first = CompletableFuture.supplyAsync(() -> perform(
                merchantApiRequest(post("/api/v1/payment-intents/" + paymentId + "/refunds"), refundKey, body)));
        assertThat(entered.await(10, TimeUnit.SECONDS)).as("provider reached").isTrue();

        dashboard(post(PAYMENTS + "/" + paymentId + "/refunds"), ownerToken, refundKey, body)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_REQUEST_IN_PROGRESS"));

        release.countDown();
        String refundId = id(first.get(10, TimeUnit.SECONDS));
        MvcResult replay = dashboard(post(PAYMENTS + "/" + paymentId + "/refunds"), ownerToken, refundKey, body)
                .andExpect(status().isCreated()).andExpect(header().string("Idempotency-Replayed", "true")).andReturn();
        assertThat(id(replay)).isEqualTo(refundId);
        assertThat(refunds.get()).isEqualTo(1);
        assertThat(count("refunds")).isEqualTo(1);
    }

    @Test
    void anotherMerchantsPaymentIsNotFoundAndNothingIsWritten() throws Exception {
        String otherKey = apiKeys.create(new CreateApiKeyCommand(OTHER, "Other merchant")).rawKey();
        String otherPayment = id(mvc.perform(post("/api/v1/payment-intents").header(HttpHeaders.AUTHORIZATION, bearer(otherKey))
                        .header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isCreated()).andReturn());
        Map<String, Long> before = counts();

        dashboard(post(PAYMENTS + "/" + otherPayment + "/confirm"), ownerToken, key(), null)
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PAYMENT_NOT_FOUND"));
        dashboard(post(PAYMENTS + "/" + otherPayment + "/refunds"), ownerToken, key(), "{\"amount\": 100}")
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PAYMENT_NOT_FOUND"));

        assertThat(counts()).isEqualTo(before);
        assertThat(charges.get()).isZero();
    }

    @Test
    void theSameKeyFromTwoMerchantsCreatesTwoIndependentPayments() throws Exception {
        String sharedKey = key();
        String ownerPayment = id(dashboard(post(PAYMENTS), ownerToken, sharedKey, CREATE_BODY)
                .andExpect(status().isCreated()).andReturn());
        String otherPayment = id(dashboard(post(PAYMENTS), token(OTHER, MerchantRole.OWNER), sharedKey, CREATE_BODY)
                .andExpect(status().isCreated()).andExpect(header().doesNotExist("Idempotency-Replayed")).andReturn());
        assertThat(otherPayment).isNotEqualTo(ownerPayment);
    }

    @Test
    void authenticationKeyAndStateRulesMatchTheMerchantApi() throws Exception {
        for (String authorization : List.of("", bearer(ownerKey))) {
            MockHttpServletRequestBuilder request = post(PAYMENTS).header("Idempotency-Key", key())
                    .contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY);
            if (!authorization.isEmpty()) {
                request.header(HttpHeaders.AUTHORIZATION, authorization);
            }
            mvc.perform(request).andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        }
        mvc.perform(post(PAYMENTS).header(HttpHeaders.AUTHORIZATION, ownerToken)
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
        dashboard(post(PAYMENTS), ownerToken, key(), "{\"amount\": 0, \"currency\": \"USD\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        String paymentId = createViaDashboard();
        dashboard(post(PAYMENTS + "/" + paymentId + "/refunds"), ownerToken, key(), "{\"amount\": 100}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REFUND_INVALID_PAYMENT_STATE"));
        dashboard(post(PAYMENTS + "/" + paymentId + "/confirm"), ownerToken, key(), null).andExpect(status().isOk());
        dashboard(post(PAYMENTS + "/" + paymentId + "/confirm"), ownerToken, key(), null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PAYMENT_INVALID_STATE"));
        dashboard(post(PAYMENTS + "/" + paymentId + "/refunds"), ownerToken, key(), "{\"amount\": 25001}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REFUND_AMOUNT_EXCEEDS_AVAILABLE"));

        jdbc.update("UPDATE merchants SET status = 'SUSPENDED' WHERE public_id = ?", OWNER);
        dashboard(post(PAYMENTS), ownerToken, key(), CREATE_BODY)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MERCHANT_SUSPENDED"));
    }

    private String createViaDashboard() throws Exception {
        return id(dashboard(post(PAYMENTS), ownerToken, key(), CREATE_BODY).andExpect(status().isCreated()).andReturn());
    }

    private String createViaMerchantApi(String idempotencyKey) throws Exception {
        return id(merchantApi(post("/api/v1/payment-intents"), idempotencyKey, CREATE_BODY)
                .andExpect(status().isCreated()).andReturn());
    }

    private ResultActions dashboard(MockHttpServletRequestBuilder request, String token, String idempotencyKey, String body)
            throws Exception {
        return mvc.perform(dashboardRequest(request, token, idempotencyKey, body));
    }

    private ResultActions merchantApi(MockHttpServletRequestBuilder request, String idempotencyKey, String body)
            throws Exception {
        return mvc.perform(merchantApiRequest(request, idempotencyKey, body));
    }

    private static MockHttpServletRequestBuilder dashboardRequest(
            MockHttpServletRequestBuilder request, String token, String idempotencyKey, String body) {
        return withBody(request.header(HttpHeaders.AUTHORIZATION, token).header("Idempotency-Key", idempotencyKey), body);
    }

    private MockHttpServletRequestBuilder merchantApiRequest(
            MockHttpServletRequestBuilder request, String idempotencyKey, String body) {
        return withBody(request.header(HttpHeaders.AUTHORIZATION, bearer(ownerKey)).header("Idempotency-Key", idempotencyKey), body);
    }

    private static MockHttpServletRequestBuilder withBody(MockHttpServletRequestBuilder request, String body) {
        return body == null ? request : request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private MvcResult perform(MockHttpServletRequestBuilder request) {
        try {
            return mvc.perform(request).andReturn();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private void gate() {
        entered = new CountDownLatch(1);
        release = new CountDownLatch(1);
    }

    private void pauseIfGated() throws InterruptedException {
        CountDownLatch gateEntered = entered;
        CountDownLatch gateRelease = release;
        if (gateEntered != null && gateRelease != null) {
            gateEntered.countDown();
            assertThat(gateRelease.await(10, TimeUnit.SECONDS)).as("released").isTrue();
        }
    }

    private Map<String, Long> counts() {
        return Map.of(
                "payment_intents", count("payment_intents"),
                "payment_transactions", count("payment_transactions"),
                "refunds", count("refunds"),
                "idempotency_records", count("idempotency_records"),
                "outbox_events", count("outbox_events"));
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private void merchant(String publicId) {
        jdbc.update("""
                INSERT INTO merchants (public_id, name, status, created_at, updated_at)
                VALUES (?, 'Dashboard command merchant', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, publicId);
    }

    private String token(String merchantPublicId, MerchantRole role) {
        Instant now = Instant.now();
        return "Bearer " + jwtEncoder.encode(JwtEncoderParameters.from(JwtClaimsSet.builder()
                .issuer("https://flowpay.dev").issuedAt(now).expiresAt(now.plusSeconds(900))
                .subject("usr_dashboard_operator").claim("merchant", merchantPublicId).claim("role", role.name())
                .build())).getTokenValue();
    }

    private JsonNode data(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString()).path("data");
    }

    private String id(MvcResult result) throws Exception {
        return data(result).path("id").stringValue();
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private static String bearer(String rawKey) {
        return "Bearer " + rawKey;
    }
}
