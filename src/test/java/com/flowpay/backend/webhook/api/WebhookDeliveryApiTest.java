package com.flowpay.backend.webhook.api;

import com.flowpay.backend.merchant.application.ApiKeyManagementUseCase;
import com.flowpay.backend.merchant.application.CreateApiKeyCommand;
import com.flowpay.backend.testing.PostgresIntegrationTest;
import com.flowpay.backend.webhook.application.WebhookDeliveryQueryRepository;
import com.flowpay.backend.webhook.application.WebhookDeliveryExecutionService;
import com.flowpay.backend.webhook.application.WebhookHttpClientPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WebhookDeliveryApiTest extends PostgresIntegrationTest {
    private static final String PATH = "/api/v1/merchant/webhook-deliveries";
    private static final Instant NOW = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
    private static final List<String> SUMMARY_FIELDS = List.of("id", "endpointId", "eventId", "eventType",
            "resourceType", "resourceId", "status", "attemptCount", "nextAttemptAt", "deliveredAt",
            "lastHttpStatus", "lastError", "createdAt", "updatedAt");

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtEncoder jwtEncoder;
    @Autowired private ObjectMapper json;
    @Autowired private ApiKeyManagementUseCase apiKeys;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private WebhookDeliveryExecutionService execution;
    @MockitoBean private WebhookHttpClientPort http;
    @MockitoBean private Clock clock;
    @MockitoSpyBean private WebhookDeliveryQueryRepository queries;
    private long ownerId;
    private long endpointId;
    private String token;

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenReturn(NOW);
        jdbc.execute("TRUNCATE TABLE merchants, users RESTART IDENTITY CASCADE");
        ownerId = merchant("mrc_delivery_owner");
        endpointId = endpoint("wep_owner", ownerId, "ACTIVE");
        token = token("mrc_delivery_owner");
    }

    @Test
    void listsDefaultPageNewestFirstWithStableTieOrderAndOnlyPublicFields() throws Exception {
        delivery("older", endpointId, ownerId, "payment.failed", "DEAD", NOW.minusSeconds(20));
        delivery("tie_first", endpointId, ownerId, "refund.succeeded", "PENDING", NOW.minusSeconds(10));
        delivery("tie_last", endpointId, ownerId, "payment.succeeded", "PENDING", NOW.minusSeconds(10));
        var result = mvc.perform(get(PATH).header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.meta.page").value(0))
                .andExpect(jsonPath("$.meta.size").value(20)).andExpect(jsonPath("$.meta.totalElements").value(3))
                .andExpect(jsonPath("$.meta.totalPages").value(1)).andExpect(jsonPath("$.meta.hasNext").value(false))
                .andExpect(jsonPath("$.meta.hasPrevious").value(false)).andReturn();
        JsonNode data = data(result);
        assertThat(data.get(0).path("id").stringValue()).isEqualTo("wdl_tie_last");
        assertThat(data.get(1).path("id").stringValue()).isEqualTo("wdl_tie_first");
        assertFields(data.get(0), SUMMARY_FIELDS);
        assertThat(result.getResponse().getContentAsString()).doesNotContain("secret", "ciphertext", "source_", "internalId", "version");
        verifyNoInteractions(http);
    }

    @Test
    void filtersIndividuallyAndTogetherAndPaginatesIncludingEmptyOutOfRangePages() throws Exception {
        long secondEndpoint = endpoint("wep_second", ownerId, "ACTIVE");
        delivery("one", endpointId, ownerId, "payment.failed", "DEAD", NOW.minusSeconds(30));
        delivery("two", endpointId, ownerId, "payment.failed", "PENDING", NOW.minusSeconds(20));
        delivery("three", secondEndpoint, ownerId, "refund.failed", "DEAD", NOW.minusSeconds(10));
        for (var filter : List.of(List.of("status", "DEAD"), List.of("endpointId", "wep_owner"),
                List.of("eventType", "payment.failed"))) {
            mvc.perform(get(PATH).header(HttpHeaders.AUTHORIZATION, token).param(filter.get(0), filter.get(1)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.meta.totalElements").value(2));
        }
        mvc.perform(get(PATH).header(HttpHeaders.AUTHORIZATION, token).param("status", "DEAD")
                        .param("endpointId", "wep_owner").param("eventType", "payment.failed"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value("wdl_one"));
        mvc.perform(get(PATH).header(HttpHeaders.AUTHORIZATION, token).param("page", "1").param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value("wdl_two"))
                .andExpect(jsonPath("$.meta.totalElements").value(3)).andExpect(jsonPath("$.meta.totalPages").value(3))
                .andExpect(jsonPath("$.meta.hasNext").value(true)).andExpect(jsonPath("$.meta.hasPrevious").value(true));
        mvc.perform(get(PATH).header(HttpHeaders.AUTHORIZATION, token).param("page", "4").param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0))
                .andExpect(jsonPath("$.meta.totalElements").value(3)).andExpect(jsonPath("$.meta.hasNext").value(false));
        mvc.perform(get(PATH).header(HttpHeaders.AUTHORIZATION, token).param("size", "100"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.meta.size").value(100));
        mvc.perform(get(PATH).header(HttpHeaders.AUTHORIZATION, token).param("page", "2147483647").param("size", "100"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0))
                .andExpect(jsonPath("$.meta.hasNext").value(false));
    }

    @Test
    void detailOrdersAttemptsByNumberAndPreservesOpenAttemptUnknownFields() throws Exception {
        long id = delivery("history", endpointId, ownerId, "payment.failed", "DELIVERING", NOW.minusSeconds(10));
        attempt(id, 3, false);
        attempt(id, 1, true);
        attempt(id, 2, true);
        var result = mvc.perform(get(PATH + "/wdl_history").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.eventId").value("evt_history"))
                .andExpect(jsonPath("$.data.resourceType").value("PAYMENT_INTENT"))
                .andExpect(jsonPath("$.data.resourceId").value("pi_history"))
                .andExpect(jsonPath("$.data.attempts[0].attemptNo").value(1))
                .andExpect(jsonPath("$.data.attempts[1].attemptNo").value(2))
                .andExpect(jsonPath("$.data.attempts[2].attemptNo").value(3)).andReturn();
        JsonNode detail = data(result);
        var fields = new java.util.ArrayList<>(SUMMARY_FIELDS);
        fields.add("attempts");
        assertFields(detail, fields);
        for (JsonNode attempt : detail.path("attempts")) {
            assertFields(attempt, List.of("attemptNo", "startedAt", "finishedAt", "httpStatus", "durationMs", "errorMessage"));
        }
        JsonNode open = detail.path("attempts").get(2);
        for (String field : List.of("finishedAt", "httpStatus", "durationMs", "errorMessage")) {
            assertThat(open.path(field).isNull()).isTrue();
        }
        assertThat(result.getResponse().getContentAsString()).doesNotContain("source_history", "ciphertext", "internalId", "deliveryId", "leaseExpiresAt");
    }

    @Test
    void retryReturns202AndOnlySchedulesWithoutResettingCountOrHistoryOrSendingHttp() throws Exception {
        long id = delivery("retry", endpointId, ownerId, "refund.failed", "DEAD", NOW.minusSeconds(10));
        for (int no = 1; no <= 6; no++) attempt(id, no, true);
        var history = jdbc.queryForList("SELECT * FROM webhook_delivery_attempts WHERE delivery_id = ? ORDER BY attempt_no", id);
        var result = mvc.perform(post(PATH + "/wdl_retry/retry").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.data.status").value("RETRYING"))
                .andExpect(jsonPath("$.data.attemptCount").value(6))
                .andExpect(jsonPath("$.data.nextAttemptAt").value(NOW.toString()))
                .andExpect(jsonPath("$.data.lastHttpStatus").value(503))
                .andExpect(jsonPath("$.data.lastError").value("HTTP_STATUS")).andReturn();
        assertFields(data(result), SUMMARY_FIELDS);
        assertThat(jdbc.queryForList("SELECT * FROM webhook_delivery_attempts WHERE delivery_id = ? ORDER BY attempt_no", id))
                .isEqualTo(history);
        assertThat(jdbc.queryForObject("SELECT version FROM webhook_deliveries WHERE id = ?", Long.class, id)).isEqualTo(1);
        verifyNoInteractions(http);
        mvc.perform(post(PATH + "/wdl_retry/retry").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WEBHOOK_INVALID_STATE"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "RETRYING", "DELIVERING", "DELIVERED"})
    void refusesNonDeadStateWithoutMutation(String currentStatus) throws Exception {
        long id = delivery("state", endpointId, ownerId, "payment.succeeded", currentStatus, NOW.minusSeconds(10));
        mvc.perform(post(PATH + "/wdl_state/retry").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WEBHOOK_INVALID_STATE"));
        assertThat(jdbc.queryForObject("SELECT status FROM webhook_deliveries WHERE id = ?", String.class, id)).isEqualTo(currentStatus);
        assertThat(jdbc.queryForObject("SELECT version FROM webhook_deliveries WHERE id = ?", Long.class, id)).isZero();
        verifyNoInteractions(http);
    }

    @Test
    void workerCanClaimTheScheduledManualRetryAsAttemptSevenWithoutResettingHistory() throws Exception {
        long id = delivery("worker_later", endpointId, ownerId, "payment.failed", "DEAD", NOW.minusSeconds(10));
        for (int no = 1; no <= 6; no++) attempt(id, no, true);
        mvc.perform(post(PATH + "/wdl_worker_later/retry").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isAccepted());
        var claimed = execution.claim(id, endpointId).orElseThrow();
        assertThat(claimed.attemptNo()).isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM webhook_delivery_attempts WHERE delivery_id = ?", Long.class, id))
                .isEqualTo(7);
        mvc.perform(get(PATH + "/wdl_worker_later").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("DELIVERING"))
                .andExpect(jsonPath("$.data.attemptCount").value(7))
                .andExpect(jsonPath("$.data.attempts[6].attemptNo").value(7));
        verifyNoInteractions(http);
    }

    @Test
    void disabledEndpointCannotBeManuallyRetried() throws Exception {
        delivery("disabled", endpointId, ownerId, "payment.failed", "DEAD", NOW.minusSeconds(10));
        jdbc.update("UPDATE webhook_endpoints SET status = 'DISABLED' WHERE id = ?", endpointId);
        mvc.perform(post(PATH + "/wdl_disabled/retry").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WEBHOOK_INVALID_STATE"));
        verifyNoInteractions(http);
    }

    @Test
    void hidesUnknownForeignAndContradictoryOwnershipForReadsAndRetry() throws Exception {
        long foreignMerchant = merchant("mrc_foreign");
        long foreignEndpoint = endpoint("wep_foreign", foreignMerchant, "ACTIVE");
        delivery("foreign", foreignEndpoint, foreignMerchant, "payment.failed", "DEAD", NOW.minusSeconds(10));
        delivery("foreign_event", endpointId, foreignMerchant, "payment.failed", "DEAD", NOW.minusSeconds(10));
        delivery("foreign_endpoint", foreignEndpoint, ownerId, "payment.failed", "DEAD", NOW.minusSeconds(10));
        for (String id : List.of("wdl_unknown", "wdl_foreign", "wdl_foreign_event", "wdl_foreign_endpoint")) {
            for (var request : List.of(get(PATH + "/" + id), post(PATH + "/" + id + "/retry"))) {
                mvc.perform(request.header(HttpHeaders.AUTHORIZATION, token)).andExpect(status().isNotFound())
                        .andExpect(jsonPath("$.code").value("WEBHOOK_DELIVERY_NOT_FOUND"))
                        .andExpect(jsonPath("$.requestId").isString());
            }
        }
        for (String filter : List.of("wep_foreign", "wep_unknown")) {
            mvc.perform(get(PATH).header(HttpHeaders.AUTHORIZATION, token).param("endpointId", filter))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0));
        }
        mvc.perform(get(PATH).header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.meta.totalElements").value(0));
        verifyNoInteractions(http);
    }

    @Test
    void requiresDashboardJwtForAllRoutesAndRejectsMerchantApiKey() throws Exception {
        String apiKey = apiKeys.create(new CreateApiKeyCommand("mrc_delivery_owner", "Delivery auth test")).rawKey();
        for (String authorization : List.of("", "Bearer " + apiKey)) {
            for (var request : List.of(get(PATH), get(PATH + "/wdl_unknown"), post(PATH + "/wdl_unknown/retry"))) {
                mvc.perform(request.header(HttpHeaders.AUTHORIZATION, authorization)).andExpect(status().isUnauthorized())
                        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
            }
        }
        verifyNoInteractions(http);
    }

    @ParameterizedTest
    @CsvSource({"page,-1", "size,0", "size,-1", "size,101", "status,unknown", "status,dead",
            "eventType,PAYMENT.FAILED", "eventType,payment.unknown", "endpointId,123", "page,abc", "size,abc"})
    void rejectsInvalidFiltersAndPagination(String parameter, String value) throws Exception {
        mvc.perform(get(PATH).header(HttpHeaders.AUTHORIZATION, token).param(parameter, value))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void suspendedMerchantCannotReadOrRetryDeliveries() throws Exception {
        delivery("suspended", endpointId, ownerId, "payment.failed", "DEAD", NOW.minusSeconds(10));
        jdbc.update("UPDATE merchants SET status = 'SUSPENDED' WHERE id = ?", ownerId);
        for (var request : List.of(get(PATH), get(PATH + "/wdl_suspended"), post(PATH + "/wdl_suspended/retry"))) {
            mvc.perform(request.header(HttpHeaders.AUTHORIZATION, token)).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("MERCHANT_SUSPENDED"));
        }
    }

    @Test
    void concurrentRetriesBothReadDeadButOnlyOneSchedulesAndTheOtherReceives409() throws Exception {
        long id = delivery("concurrent", endpointId, ownerId, "payment.failed", "DEAD", NOW.minusSeconds(10));
        var reads = new AtomicInteger();
        var bothRead = new CountDownLatch(2);
        doAnswer(call -> {
            Object result = call.callRealMethod();
            if (reads.getAndIncrement() < 2) {
                bothRead.countDown();
                assertThat(bothRead.await(10, TimeUnit.SECONDS)).isTrue();
            }
            return result;
        }).when(queries).findByPublicIdAndMerchantId(anyString(), anyLong());
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> mvc.perform(post(PATH + "/wdl_concurrent/retry")
                    .header(HttpHeaders.AUTHORIZATION, token)).andReturn().getResponse().getStatus());
            var second = pool.submit(() -> mvc.perform(post(PATH + "/wdl_concurrent/retry")
                    .header(HttpHeaders.AUTHORIZATION, token)).andReturn().getResponse().getStatus());
            assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(202, 409);
        }
        assertThat(jdbc.queryForObject("SELECT version FROM webhook_deliveries WHERE id = ?", Long.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM webhook_deliveries WHERE id = ?", String.class, id)).isEqualTo("RETRYING");
        verifyNoInteractions(http);
    }

    @Test
    void retryWaitsForEndpointDisableAndRechecksStatusAfterLockIsReleased() throws Exception {
        delivery("disable_race", endpointId, ownerId, "payment.failed", "DEAD", NOW.minusSeconds(10));
        var ownedRead = new CountDownLatch(1);
        doAnswer(call -> {
            Object result = call.callRealMethod();
            ownedRead.countDown();
            return result;
        }).when(queries).findByPublicIdAndMerchantId(anyString(), anyLong());
        try (var pool = Executors.newSingleThreadExecutor()) {
            var future = new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<MvcResult>>();
            new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                jdbc.queryForList("SELECT id FROM webhook_endpoints WHERE id = ? FOR UPDATE", Long.class, endpointId);
                future.set(pool.submit(() -> mvc.perform(post(PATH + "/wdl_disable_race/retry")
                        .header(HttpHeaders.AUTHORIZATION, token)).andReturn()));
                try {
                    assertThat(ownedRead.await(10, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
                jdbc.update("UPDATE webhook_endpoints SET status = 'DISABLED' WHERE id = ?", endpointId);
            });
            var response = future.get().get(15, TimeUnit.SECONDS).getResponse();
            assertThat(response.getStatus()).isEqualTo(409);
            assertThat(json.readTree(response.getContentAsByteArray()).path("code").stringValue()).isEqualTo("WEBHOOK_INVALID_STATE");
        }
        verifyNoInteractions(http);
    }

    private long merchant(String publicId) {
        return jdbc.queryForObject("""
                INSERT INTO merchants (public_id, name, status, created_at, updated_at)
                VALUES (?, 'Delivery merchant', 'ACTIVE', ?, ?) RETURNING id
                """, Long.class, publicId, utc(NOW.minusSeconds(60)), utc(NOW.minusSeconds(60)));
    }

    private long endpoint(String publicId, long merchantId, String status) {
        long id = jdbc.queryForObject("""
                INSERT INTO webhook_endpoints (public_id, merchant_id, url, secret_ciphertext, status, created_at, updated_at)
                VALUES (?, ?, 'https://example.com/hook', 'ciphertext-do-not-expose', ?, ?, ?) RETURNING id
                """, Long.class, publicId, merchantId, status, utc(NOW.minusSeconds(60)), utc(NOW.minusSeconds(60)));
        jdbc.update("INSERT INTO webhook_endpoint_events (endpoint_id, event_type) VALUES (?, 'payment.failed')", id);
        return id;
    }

    private long delivery(String suffix, long endpoint, long merchant, String eventType, String status, Instant createdAt) {
        boolean refund = eventType.startsWith("refund.");
        long event = jdbc.queryForObject("""
                INSERT INTO webhook_events (public_id, source_event_id, merchant_id, event_type, resource_type,
                    resource_id, payload, occurred_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, '{}', ?, ?) RETURNING id
                """, Long.class, "evt_" + suffix, "source_" + suffix, merchant, eventType,
                refund ? "REFUND" : "PAYMENT_INTENT", (refund ? "re_" : "pi_") + suffix, utc(createdAt), utc(createdAt));
        boolean scheduled = status.equals("PENDING") || status.equals("RETRYING");
        return jdbc.queryForObject("""
                INSERT INTO webhook_deliveries (public_id, webhook_event_id, webhook_endpoint_id, status,
                    attempt_count, next_attempt_at, lease_expires_at, delivered_at, last_http_status, last_error,
                    created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                """, Long.class, "wdl_" + suffix, event, endpoint, status, status.equals("PENDING") ? 0 : 6,
                scheduled ? utc(NOW) : null, status.equals("DELIVERING") ? utc(NOW.plusSeconds(30)) : null,
                status.equals("DELIVERED") ? utc(NOW) : null, status.equals("DEAD") ? 503 : null,
                status.equals("DEAD") ? "HTTP_STATUS" : null, utc(createdAt), utc(NOW));
    }

    private void attempt(long id, int no, boolean completed) {
        jdbc.update("""
                INSERT INTO webhook_delivery_attempts (delivery_id, attempt_no, started_at, finished_at,
                    http_status, duration_ms, error_message, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, id, no, utc(NOW.minusSeconds(10)), completed ? utc(NOW.minusSeconds(9)) : null,
                completed ? 503 : null, completed ? 1000 : null, completed ? "HTTP_STATUS" : null, utc(NOW.minusSeconds(10)));
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private JsonNode data(MvcResult result) {
        return json.readTree(result.getResponse().getContentAsByteArray()).path("data");
    }

    private static void assertFields(JsonNode node, List<String> fields) {
        assertThat(node.properties()).extracting(java.util.Map.Entry::getKey).containsExactlyInAnyOrderElementsOf(fields);
    }

    private String token(String merchantId) {
        return "Bearer " + jwtEncoder.encode(JwtEncoderParameters.from(JwtClaimsSet.builder()
                .issuer("https://flowpay.dev").issuedAt(NOW).expiresAt(NOW.plusSeconds(900))
                .subject("usr_delivery_owner").claim("merchant", merchantId).claim("role", "MEMBER")
                .build())).getTokenValue();
    }
}
