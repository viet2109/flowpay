package com.flowpay.backend.infrastructure.messaging.rabbit;

import com.flowpay.backend.infrastructure.messaging.FlowPayMessagingProperties;
import com.flowpay.backend.infrastructure.messaging.WebhookMessagingProperties;
import com.flowpay.backend.infrastructure.messaging.outbox.relay.OutboxRelayService;
import com.flowpay.backend.merchant.application.ApiKeyManagementUseCase;
import com.flowpay.backend.merchant.application.CreateApiKeyCommand;
import com.flowpay.backend.payment.application.PaymentProviderPort;
import com.flowpay.backend.payment.application.PaymentProviderResult;
import com.flowpay.backend.payment.domain.ProviderOutcome;
import com.flowpay.backend.refund.application.RefundProviderPort;
import com.flowpay.backend.refund.application.RefundProviderResult;
import com.flowpay.backend.refund.domain.RefundProviderOutcome;
import com.flowpay.backend.testing.PostgresIntegrationTest;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real source services, PostgreSQL, Outbox, Rabbit consumers, scheduler and signed loopback HTTP.
 * Only the external payment/refund provider ports supply controlled outcomes. */
@SpringBootTest(properties = {
        "flowpay.webhook.delivery-worker.enabled=true",
        "flowpay.webhook.delivery-worker.fixed-delay=50ms",
        "flowpay.webhook.delivery-worker.lease-timeout=5s",
        "flowpay.webhook.http.connect-timeout=1s",
        "flowpay.webhook.http.request-timeout=1s",
        "flowpay.webhook.url-policy.allow-insecure-localhost=true"
})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
@Import(PhaseSevenWebhookEndToEndIntegrationTest.TimeConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PhaseSevenWebhookEndToEndIntegrationTest extends PostgresIntegrationTest {
    private static final String MERCHANT = "mrc_p7_end_to_end";
    private static final String ENDPOINTS = "/api/v1/merchant/webhook-endpoints";
    private static final String DELIVERIES = "/api/v1/merchant/webhook-deliveries";
    private static final String PAYMENTS = "/api/v1/payment-intents";
    private static final List<String> ALL_EVENTS = List.of("payment.processing", "payment.succeeded", "payment.failed",
            "refund.processing", "refund.succeeded", "refund.failed");
    private static final List<Duration> RETRY_DELAYS = List.of(Duration.ofSeconds(10), Duration.ofSeconds(30),
            Duration.ofMinutes(2), Duration.ofMinutes(10), Duration.ofHours(1));
    private static final String CLEAN = """
            TRUNCATE TABLE merchants, users, ledger_entries, ledger_transactions, ledger_accounts,
                outbox_events RESTART IDENTITY CASCADE
            """;

    @Container private static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4.3-management-alpine");
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtEncoder jwt;
    @Autowired private ApiKeyManagementUseCase apiKeys;
    @Autowired private OutboxRelayService relay;
    @Autowired private RabbitAdmin admin;
    @Autowired private RabbitListenerEndpointRegistry listeners;
    @Autowired private FlowPayMessagingProperties messaging;
    @Autowired private WebhookMessagingProperties webhook;
    @Autowired private TestClock clock;
    @MockitoBean private PaymentProviderPort paymentProvider;
    @MockitoBean private RefundProviderPort refundProvider;
    private HttpServer receiver;
    private ExecutorService receiverThreads;
    private final Map<String, Route> routes = new ConcurrentHashMap<>();
    private String apiKey;
    private String dashboardToken;

    @DynamicPropertySource
    static void broker(DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
        registry.add("flowpay.messaging.webhook-consumer.enabled", () -> "true");
        registry.add("flowpay.messaging.ledger-consumer.enabled", () -> "true");
    }

    @BeforeEach
    void setUp() throws Exception {
        // No deliveries survive cleanup, so the actual scheduler cannot contact a prior test's URL.
        jdbc.execute(CLEAN);
        clock.set(Instant.now().truncatedTo(ChronoUnit.MICROS));
        purgeQueues();
        when(paymentProvider.charge(any())).thenReturn(paymentResult(ProviderOutcome.SUCCESS));
        when(refundProvider.refund(any())).thenReturn(refundResult(RefundProviderOutcome.SUCCESS));
        jdbc.update("""
                INSERT INTO merchants (public_id, name, status, created_at, updated_at)
                VALUES (?, 'Phase seven merchant', 'ACTIVE', ?, ?)
                """, MERCHANT, utc(clock.instant()), utc(clock.instant()));
        apiKey = apiKeys.create(new CreateApiKeyCommand(MERCHANT, "Phase seven verification")).rawKey();
        Instant realNow = Instant.now();
        dashboardToken = "Bearer " + jwt.encode(JwtEncoderParameters.from(JwtClaimsSet.builder()
                .issuer("https://flowpay.dev").issuedAt(realNow).expiresAt(realNow.plusSeconds(900))
                .subject("usr_p7_end_to_end").claim("merchant", MERCHANT).claim("role", "MEMBER")
                .build())).getTokenValue();
        receiverThreads = Executors.newVirtualThreadPerTaskExecutor();
        receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        receiver.setExecutor(receiverThreads);
        receiver.createContext("/", exchange -> {
            Route route = routes.get(exchange.getRequestURI().getPath());
            if (route == null) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            try {
                var headers = exchange.getRequestHeaders();
                route.receipts.add(new Receipt(exchange.getRequestMethod(), headers.getFirst("Content-Type"),
                        headers.getFirst("User-Agent"), headers.getFirst("FlowPay-Event-Id"),
                        headers.getFirst("FlowPay-Signature"), exchange.getRequestBody().readAllBytes()));
                // A separate receiver thread/connection must already see the committed claim and OPEN history.
                assertThat(count("""
                        SELECT COUNT(*) FROM webhook_deliveries d
                        JOIN webhook_events e ON e.id = d.webhook_event_id
                        JOIN webhook_endpoints p ON p.id = d.webhook_endpoint_id
                        JOIN webhook_delivery_attempts a ON a.delivery_id = d.id AND a.attempt_no = d.attempt_count
                        WHERE e.public_id = ? AND p.public_id = ? AND d.status = 'DELIVERING' AND a.finished_at IS NULL
                        """, headers.getFirst("FlowPay-Event-Id"), route.endpointId)).isOne();
                if (route.timeoutFirst.compareAndSet(true, false)) {
                    if (!route.releaseTimeout.await(10, TimeUnit.SECONDS)) {
                        route.failure.set(new AssertionError("Timeout receiver gate was not released"));
                    }
                    return; // No response headers: exercise the real client's request timeout.
                }
                int code = route.responseStatus.get();
                byte[] response = "merchant-private-response-not-for-history".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(code, code == 204 ? -1 : response.length);
                if (code != 204) exchange.getResponseBody().write(response);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (IOException disconnected) {
                // A timed-out client may close its connection; that is expected by this receiver.
            } catch (RuntimeException | AssertionError exception) {
                route.failure.set(exception);
            } finally {
                exchange.close();
            }
        });
        receiver.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            for (Route route : routes.values()) route.releaseTimeout.countDown();
            for (String id : jdbc.queryForList("SELECT public_id FROM webhook_endpoints", String.class)) {
                mvc.perform(delete(ENDPOINTS + "/" + id).header(HttpHeaders.AUTHORIZATION, dashboardToken))
                        .andExpect(status().isNoContent());
            }
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                    assertThat(count("SELECT COUNT(*) FROM webhook_deliveries WHERE status = 'DELIVERING'")).isZero());
            // Both listeners finish before another test truncates the shared PostgreSQL fixture.
            listeners.stop();
            purgeQueues();
            jdbc.execute(CLEAN);
        } finally {
            if (receiver != null) receiver.stop(0);
            if (receiverThreads != null) receiverThreads.shutdownNow();
            routes.clear();
            listeners.start();
        }
    }

    @Test
    void paymentAndRefundReachSignedHttpWithSubsetSubscriptionsAndLedgerAtTheSameTime() throws Exception {
        RegisteredEndpoint all = register("/all", ALL_EVENTS, 204);
        RegisteredEndpoint successOnly = register("/success", List.of("payment.succeeded", "refund.succeeded"), 200);
        RegisteredEndpoint disabled = register("/disabled", ALL_EVENTS, 204);
        disable(disabled);
        String payment = createPayment("success");
        JsonNode confirmation = confirm(payment, "success", 200);
        assertThat(confirmation.path("paymentStatus").stringValue()).isEqualTo("SUCCEEDED");
        assertThat(confirm(payment, "success", 200)).isEqualTo(confirmation);
        relayAndAwait(2, 3, 1);
        JsonNode refund = refund(payment, "success", 201);
        String refundId = refund.path("id").stringValue();
        assertThat(refund.path("status").stringValue()).isEqualTo("SUCCEEDED");
        assertThat(refund(payment, "success", 201)).isEqualTo(refund);
        relayAndAwait(4, 6, 2);
        assertReceivedTypes(all, List.of("payment.processing", "payment.succeeded", "refund.processing", "refund.succeeded"));
        assertReceivedTypes(successOnly, List.of("payment.succeeded", "refund.succeeded"));
        assertThat(route(disabled).receipts).isEmpty();
        assertLedgerBalanced(2);
        assertThat(count("SELECT COUNT(*) FROM webhook_deliveries WHERE status = 'DELIVERED'")).isEqualTo(6);
        verify(paymentProvider, times(1)).charge(any());
        verify(refundProvider, times(1)).refund(any());
        for (Receipt receipt : route(all).receipts) {
            assertSignature(receipt, all.secret());
            JsonNode body = json.readTree(receipt.body());
            JsonNode resource = body.path("data").path(body.path("type").stringValue().startsWith("refund.") ? "refund" : "payment");
            boolean refundEvent = body.path("type").stringValue().startsWith("refund.");
            assertThat(resource.path("id").stringValue()).isEqualTo(refundEvent ? refundId : payment);
            if (refundEvent) assertThat(resource.path("paymentId").stringValue()).isEqualTo(payment);
            assertThat(resource.path("status").stringValue()).isEqualTo(body.path("type").stringValue().endsWith("processing") ? "PROCESSING" : "SUCCEEDED");
            assertThat(resource.path("amount").longValue()).isEqualTo(body.path("type").stringValue().startsWith("refund.") ? 200_000 : 1_000_000);
            assertThat(resource.path("currency").stringValue()).isEqualTo("VND");
            assertThat(resource.path("failureCode").isNull()).isTrue();
            assertThat(resource.path("failureMessage").isNull()).isTrue();
            assertThat(body.path("createdAt").stringValue()).isEqualTo(clock.instant().toString());
            assertThat(body.toString()).doesNotContain("merchantInternalId", "sourceEventId", "orderId", "ciphertext", apiKey);
        }
        for (Receipt receipt : route(successOnly).receipts) {
            assertSignature(receipt, successOnly.secret());
            assertThat(route(all).receipts).anySatisfy(other -> {
                assertThat(other.eventId()).isEqualTo(receipt.eventId());
                assertThat(other.body()).isEqualTo(receipt.body());
            });
        }
    }

    @ParameterizedTest
    @EnumSource(value = ProviderOutcome.class, names = {"DECLINED", "TECHNICAL_FAILURE", "UNKNOWN"})
    void paymentFailureAndUnknownHaveOnlyTheApprovedPublicEvents(ProviderOutcome outcome) throws Exception {
        RegisteredEndpoint endpoint = register("/payment-outcome", ALL_EVENTS, 204);
        when(paymentProvider.charge(any())).thenReturn(paymentResult(outcome));
        String payment = createPayment("payment-outcome");
        JsonNode result = confirm(payment, "payment-outcome", outcome == ProviderOutcome.UNKNOWN ? 202 : 200);
        boolean unknown = outcome == ProviderOutcome.UNKNOWN;
        assertThat(result.path("paymentStatus").stringValue()).isEqualTo(unknown ? "PROCESSING" : "FAILED");
        assertThat(result.path("transactionStatus").stringValue()).isEqualTo(unknown ? "UNKNOWN" : "FAILED");
        relayAndAwait(unknown ? 1 : 2, unknown ? 1 : 2, 0);
        assertReceivedTypes(endpoint, unknown ? List.of("payment.processing") : List.of("payment.processing", "payment.failed"));
        assertFailureFacts(endpoint, outcome.name(), unknown);
        mvc.perform(get(PAYMENTS + "/" + payment).header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value(unknown ? "PROCESSING" : "FAILED"));
    }

    @ParameterizedTest
    @EnumSource(value = RefundProviderOutcome.class, names = {"DECLINED", "TECHNICAL_FAILURE", "UNKNOWN"})
    void refundFailureAndUnknownKeepPaymentCapacityAndLedgerSemantics(RefundProviderOutcome outcome) throws Exception {
        RegisteredEndpoint endpoint = register("/refund-outcome", List.of("refund.processing", "refund.failed", "refund.succeeded"), 204);
        String payment = createPayment("refund-outcome");
        confirm(payment, "refund-outcome", 200);
        relayAndAwait(2, 0, 1);
        when(refundProvider.refund(any())).thenReturn(refundResult(outcome));
        boolean unknown = outcome == RefundProviderOutcome.UNKNOWN;
        JsonNode refund = refund(payment, "refund-outcome", unknown ? 202 : 201);
        assertThat(refund.path("status").stringValue()).isEqualTo(unknown ? "PROCESSING" : "FAILED");
        relayAndAwait(unknown ? 3 : 4, unknown ? 1 : 2, 1);
        assertReceivedTypes(endpoint, unknown ? List.of("refund.processing") : List.of("refund.processing", "refund.failed"));
        assertFailureFacts(endpoint, outcome.name(), unknown);
        assertThat(count("SELECT refund_reserved_minor FROM payment_intents WHERE public_id = ?", payment))
                .isEqualTo(unknown ? 200_000 : 0);
        assertThat(count("SELECT refunded_amount_minor FROM payment_intents WHERE public_id = ?", payment)).isZero();
        mvc.perform(get("/api/v1/refunds/" + refund.path("id").stringValue()).header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value(unknown ? "PROCESSING" : "FAILED"));
        assertLedgerBalanced(1);
    }

    @Test
    void duplicateRabbitPublicationKeepsOnePublicEventAndTheOriginalEndpointSnapshot() throws Exception {
        RegisteredEndpoint original = register("/original", List.of("payment.succeeded"), 204);
        String payment = createPayment("duplicate");
        confirm(payment, "duplicate", 200);
        relayAndAwait(2, 1, 1);
        Receipt sent = route(original).receipts.getFirst();
        RegisteredEndpoint late = register("/late", List.of("payment.succeeded"), 204);
        // Republish the actual persisted source envelope through the real confirmed relay/broker.
        jdbc.update("UPDATE outbox_events SET status = 'PENDING', published_at = NULL WHERE aggregate_id = ?", payment);
        assertThat(relay.relayDueEvents().publishedCount()).isEqualTo(2);
        // A new source event is a FIFO marker, not an arbitrary delay or an external exactly-once assertion.
        when(paymentProvider.charge(any())).thenReturn(paymentResult(ProviderOutcome.UNKNOWN));
        String marker = createPayment("duplicate-marker");
        confirm(marker, "duplicate-marker", 202);
        relayAndAwait(3, 1, 1);
        assertThat(count("SELECT COUNT(*) FROM webhook_events WHERE event_type = 'payment.succeeded' AND resource_id = ?", payment)).isOne();
        assertThat(count("SELECT COUNT(*) FROM webhook_deliveries")).isOne();
        assertThat(route(late).receipts).isEmpty();
        assertThat(jdbc.queryForObject("SELECT public_id FROM webhook_events WHERE event_type = 'payment.succeeded'", String.class))
                .isEqualTo(sent.eventId());
        assertLedgerBalanced(1);
    }

    @Test
    void timeoutThenSecretRotationRetriesTheSameBodyWithTheNewSecretAndRetainedHistory() throws Exception {
        RegisteredEndpoint endpoint = register("/timeout", List.of("payment.succeeded"), 204);
        Route route = route(endpoint);
        route.timeoutFirst.set(true);
        String payment = createPayment("timeout");
        confirm(payment, "timeout", 200);
        relayAndAwait(2, 1, 1);
        String delivery = deliveryId(endpoint);
        awaitDelivery(delivery, "RETRYING", 1);
        Receipt first = route.receipts.getFirst();
        assertSignature(first, endpoint.secret());
        JsonNode rotated = data(mvc.perform(post(ENDPOINTS + "/" + endpoint.id() + "/rotate-secret")
                .header(HttpHeaders.AUTHORIZATION, dashboardToken)).andExpect(status().isOk()).andReturn());
        String nextSecret = rotated.path("secret").stringValue();
        route.releaseTimeout.countDown();
        Instant nextAttempt = nextAttempt(delivery);
        clock.set(nextAttempt);
        awaitDelivery(delivery, "DELIVERED", 2);
        assertThat(route.receipts).hasSizeGreaterThanOrEqualTo(2);
        Receipt retried = route.receipts.get(1);
        assertSignature(retried, nextSecret);
        assertThat(independentSignature(retried, endpoint.secret())).isNotEqualTo(retried.signature());
        assertThat(retried.eventId()).isEqualTo(first.eventId());
        assertThat(retried.body()).isEqualTo(first.body());
        assertThat(retried.signature()).isNotEqualTo(first.signature());
        var detail = deliveryDetail(delivery);
        assertThat(detail.path("attempts").size()).isEqualTo(2);
        assertThat(detail.path("attempts").get(0).path("errorMessage").stringValue()).isEqualTo("REQUEST_TIMEOUT");
        assertThat(detail.path("attempts").get(0).path("httpStatus").isNull()).isTrue();
        assertThat(detail.path("attempts").get(1).path("httpStatus").intValue()).isEqualTo(204);
        assertThat(detail.toString()).doesNotContain(endpoint.secret(), nextSecret, "merchant-private-response");
        assertThat(route.failure.get()).isNull();
    }

    @Test
    void sixHttpFailuresBecomeDeadAndManualRetrySucceedsAsAttemptSeven() throws Exception {
        RegisteredEndpoint endpoint = register("/dead", List.of("payment.succeeded"), 503);
        String payment = createPayment("dead");
        confirm(payment, "dead", 200);
        relayAndAwait(2, 1, 1);
        String id = deliveryId(endpoint);
        for (int attempt = 1; attempt <= 6; attempt++) {
            awaitDelivery(id, attempt == 6 ? "DEAD" : "RETRYING", attempt);
            if (attempt < 6) {
                Instant next = nextAttempt(id);
                Duration base = RETRY_DELAYS.get(attempt - 1);
                assertThat(next).isBetween(clock.instant().plus(base), clock.instant().plusNanos(base.toNanos() * 12 / 10));
                clock.set(next);
            }
        }
        JsonNode dead = deliveryDetail(id);
        assertThat(dead.path("attempts").size()).isEqualTo(6);
        clock.set(clock.instant().plus(Duration.ofDays(1)));
        await().atMost(Duration.ofSeconds(3)).during(Duration.ofMillis(200)).untilAsserted(() ->
                assertThat(count("SELECT attempt_count FROM webhook_deliveries WHERE public_id = ?", id)).isEqualTo(6));
        route(endpoint).responseStatus.set(204);
        JsonNode scheduled = data(mvc.perform(post(DELIVERIES + "/" + id + "/retry")
                .header(HttpHeaders.AUTHORIZATION, dashboardToken)).andExpect(status().isAccepted()).andReturn());
        assertThat(scheduled.path("status").stringValue()).isEqualTo("RETRYING");
        assertThat(scheduled.path("attemptCount").intValue()).isEqualTo(6);
        awaitDelivery(id, "DELIVERED", 7);
        JsonNode delivered = deliveryDetail(id);
        assertThat(delivered.path("attempts").size()).isEqualTo(7);
        for (int i = 0; i < 6; i++) assertThat(delivered.path("attempts").get(i)).isEqualTo(dead.path("attempts").get(i));
        assertThat(delivered.path("attempts").get(6).path("httpStatus").intValue()).isEqualTo(204);
        Receipt first = route(endpoint).receipts.getFirst();
        for (Receipt receipt : route(endpoint).receipts) {
            assertSignature(receipt, endpoint.secret());
            assertThat(receipt.body()).isEqualTo(first.body());
            assertThat(receipt.eventId()).isEqualTo(first.eventId());
        }
        assertLedgerBalanced(1);
        assertThat(jdbc.queryForObject("SELECT status FROM payment_intents WHERE public_id = ?", String.class, payment)).isEqualTo("SUCCEEDED");
        assertThat(count("SELECT COUNT(*) FROM outbox_events WHERE status = 'PUBLISHED'")).isEqualTo(2);
    }

    @Test
    void endpointDisableStopsScheduledRetryAndExcludesFutureSourceEvents() throws Exception {
        RegisteredEndpoint disabled = register("/disable", List.of("payment.succeeded"), 503);
        RegisteredEndpoint healthy = register("/healthy", List.of("payment.succeeded"), 204);
        String payment = createPayment("disable");
        confirm(payment, "disable", 200);
        relayAndAwait(2, 2, 1);
        String id = deliveryId(disabled);
        awaitDelivery(id, "RETRYING", 1);
        awaitDelivery(deliveryId(healthy), "DELIVERED", 1);
        int receivedBeforeDisable = route(disabled).receipts.size();
        disable(disabled);
        clock.set(clock.instant().plus(Duration.ofHours(2)));
        String nextPayment = createPayment("after-disable");
        confirm(nextPayment, "after-disable", 200);
        relayAndAwait(4, 3, 2);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(count("SELECT COUNT(*) FROM webhook_deliveries WHERE status = 'DELIVERED'")).isEqualTo(2));
        assertThat(count("SELECT COUNT(*) FROM webhook_deliveries d JOIN webhook_endpoints p ON p.id = d.webhook_endpoint_id WHERE p.public_id = ?", disabled.id())).isOne();
        awaitDelivery(id, "DEAD", 1);
        assertThat(route(disabled).receipts).hasSize(receivedBeforeDisable);
        assertThat(route(healthy).receipts).hasSizeGreaterThanOrEqualTo(2);
        assertLedgerBalanced(2);
    }

    private RegisteredEndpoint register(String path, List<String> events, int responseStatus) throws Exception {
        routes.put(path, new Route(responseStatus));
        String url = "http://127.0.0.1:" + receiver.getAddress().getPort() + path;
        JsonNode response = data(mvc.perform(post(ENDPOINTS).header(HttpHeaders.AUTHORIZATION, dashboardToken)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("url", url, "events", events))))
                .andExpect(status().isCreated()).andReturn());
        String id = response.path("id").stringValue();
        routes.get(path).endpointId = id;
        return new RegisteredEndpoint(id, path, response.path("secret").stringValue());
    }

    private void disable(RegisteredEndpoint endpoint) throws Exception {
        mvc.perform(delete(ENDPOINTS + "/" + endpoint.id()).header(HttpHeaders.AUTHORIZATION, dashboardToken))
                .andExpect(status().isNoContent());
    }

    private String createPayment(String key) throws Exception {
        return data(mvc.perform(post(PAYMENTS).header(HttpHeaders.AUTHORIZATION, bearer())
                .header("Idempotency-Key", "create-" + key).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"amount":1000000,"currency":"VND","orderId":"ORDER-%s"}
                        """.formatted(key))).andExpect(status().isCreated()).andReturn()).path("id").stringValue();
    }

    private JsonNode confirm(String id, String key, int expectedStatus) throws Exception {
        return data(mvc.perform(post(PAYMENTS + "/" + id + "/confirm").header(HttpHeaders.AUTHORIZATION, bearer())
                .header("Idempotency-Key", "confirm-" + key)).andExpect(status().is(expectedStatus)).andReturn());
    }

    private JsonNode refund(String paymentId, String key, int expectedStatus) throws Exception {
        return data(mvc.perform(post(PAYMENTS + "/" + paymentId + "/refunds").header(HttpHeaders.AUTHORIZATION, bearer())
                .header("Idempotency-Key", "refund-" + key).contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":200000,\"reason\":\"Hoàn tiền theo yêu cầu\"}"))
                .andExpect(status().is(expectedStatus)).andReturn());
    }

    private void relayAndAwait(long eventCount, long deliveryCount, long postingCount) {
        assertThat(count("SELECT COUNT(*) FROM outbox_events")).isEqualTo(eventCount);
        assertThat(relay.relayDueEvents().failedCount()).isZero();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(count("SELECT COUNT(*) FROM webhook_events")).isEqualTo(eventCount);
            assertThat(count("SELECT COUNT(*) FROM webhook_deliveries")).isEqualTo(deliveryCount);
            assertThat(count("SELECT COUNT(*) FROM ledger_transactions")).isEqualTo(postingCount);
            assertThat(count("SELECT COUNT(*) FROM outbox_events WHERE status <> 'PUBLISHED'")).isZero();
        });
        assertNoDlq();
    }

    private void assertReceivedTypes(RegisteredEndpoint endpoint, List<String> expected) throws Exception {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            var types = route(endpoint).receipts.stream().map(receipt -> json.readTree(receipt.body()).path("type").stringValue()).toList();
            // At-least-once delivery: compare logical event identities, not an exactly-once HTTP claim.
            assertThat(types).containsAll(expected);
            assertThat(types).allMatch(expected::contains);
            assertThat(route(endpoint).failure.get()).isNull();
            assertThat(count("""
                    SELECT COUNT(*) FROM webhook_deliveries d JOIN webhook_endpoints p ON p.id = d.webhook_endpoint_id
                    WHERE p.public_id = ? AND d.status <> 'DELIVERED'
                    """, endpoint.id())).isZero();
        });
        for (Receipt receipt : route(endpoint).receipts) assertSignature(receipt, endpoint.secret());
    }

    private void assertFailureFacts(RegisteredEndpoint endpoint, String outcome, boolean unknown) {
        for (Receipt receipt : route(endpoint).receipts) {
            JsonNode body = json.readTree(receipt.body());
            String type = body.path("type").stringValue();
            var resource = body.path("data").path(type.startsWith("refund.") ? "refund" : "payment");
            assertThat(resource.path("status").stringValue()).isEqualTo(type.endsWith("failed") ? "FAILED" : "PROCESSING");
            if (type.endsWith("failed")) {
                assertThat(resource.path("failureCode").stringValue()).isEqualTo(outcome);
                assertThat(resource.path("failureMessage").stringValue()).isEqualTo("Controlled provider failure");
            } else {
                assertThat(resource.path("failureCode").isNull()).isTrue();
                assertThat(resource.path("failureMessage").isNull()).isTrue();
            }
            if (unknown) assertThat(type).endsWith("processing");
        }
    }

    private String deliveryId(RegisteredEndpoint endpoint) {
        return jdbc.queryForObject("""
                SELECT d.public_id FROM webhook_deliveries d JOIN webhook_endpoints p ON p.id = d.webhook_endpoint_id
                WHERE p.public_id = ?
                """, String.class, endpoint.id());
    }

    private void awaitDelivery(String id, String status, int attempts) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(jdbc.queryForObject("SELECT status FROM webhook_deliveries WHERE public_id = ?", String.class, id)).isEqualTo(status);
            assertThat(count("SELECT attempt_count FROM webhook_deliveries WHERE public_id = ?", id)).isEqualTo(attempts);
            assertThat(count("""
                    SELECT COUNT(*) FROM webhook_delivery_attempts a JOIN webhook_deliveries d ON d.id = a.delivery_id
                    WHERE d.public_id = ? AND a.finished_at IS NOT NULL
                    """, id)).isEqualTo(attempts);
        });
    }

    private Instant nextAttempt(String id) {
        // DB timestamps have microsecond precision; round forward so a truncated read cannot miss eligibility.
        return jdbc.queryForObject("SELECT next_attempt_at FROM webhook_deliveries WHERE public_id = ?",
                OffsetDateTime.class, id).toInstant().plusNanos(1000);
    }

    private JsonNode deliveryDetail(String id) throws Exception {
        return data(mvc.perform(get(DELIVERIES + "/" + id).header(HttpHeaders.AUTHORIZATION, dashboardToken))
                .andExpect(status().isOk()).andReturn());
    }

    private void assertSignature(Receipt receipt, String secret) throws Exception {
        assertThat(receipt.method()).isEqualTo("POST");
        assertThat(receipt.contentType()).isEqualTo("application/json");
        assertThat(receipt.userAgent()).isEqualTo("FlowPay-Webhooks/1.0");
        assertThat(receipt.eventId()).startsWith("evt_");
        assertThat(json.readTree(receipt.body()).path("id").stringValue()).isEqualTo(receipt.eventId());
        assertThat(receipt.signature()).isEqualTo(independentSignature(receipt, secret));
        String persisted = jdbc.queryForObject("SELECT payload::text FROM webhook_events WHERE public_id = ?", String.class, receipt.eventId());
        assertThat(receipt.body()).isEqualTo(persisted.getBytes(StandardCharsets.UTF_8));
    }

    private static String independentSignature(Receipt receipt, String secret) throws Exception {
        String timestamp = receipt.signature().substring(2, receipt.signature().indexOf(",v1="));
        assertThat(Long.parseLong(timestamp)).isNotNegative();
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        mac.update((timestamp + ".").getBytes(StandardCharsets.US_ASCII));
        return "t=" + timestamp + ",v1=" + HexFormat.of().formatHex(mac.doFinal(receipt.body()));
    }

    private void assertLedgerBalanced(long postings) {
        assertThat(count("SELECT COUNT(*) FROM ledger_transactions")).isEqualTo(postings);
        assertThat(count("SELECT COUNT(*) FROM ledger_entries")).isEqualTo(postings * 2);
        assertThat(count("""
                SELECT COUNT(*) FROM (
                    SELECT ledger_transaction_id FROM ledger_entries GROUP BY ledger_transaction_id
                    HAVING SUM(CASE WHEN direction = 'DEBIT' THEN amount_minor ELSE -amount_minor END) <> 0
                ) unbalanced
                """)).isZero();
    }

    private void assertNoDlq() {
        for (String queue : List.of(webhook.topology().webhookDeadLetterQueue(), messaging.topology().ledgerDeadLetterQueue())) {
            assertThat(admin.getQueueInfo(queue).getMessageCount()).isZero();
        }
    }

    private void purgeQueues() {
        for (String queue : List.of(webhook.topology().webhookQueue(), webhook.topology().webhookDeadLetterQueue(),
                messaging.topology().ledgerQueue(), messaging.topology().ledgerDeadLetterQueue())) admin.purgeQueue(queue, false);
    }

    private long count(String sql, Object... parameters) {
        return jdbc.queryForObject(sql, Long.class, parameters);
    }

    private JsonNode data(MvcResult result) {
        return json.readTree(result.getResponse().getContentAsByteArray()).path("data");
    }

    private Route route(RegisteredEndpoint endpoint) { return routes.get(endpoint.path()); }
    private String bearer() { return "Bearer " + apiKey; }
    private static OffsetDateTime utc(Instant time) { return time.atOffset(ZoneOffset.UTC); }

    private static PaymentProviderResult paymentResult(ProviderOutcome outcome) {
        return new PaymentProviderResult("SIMULATOR", outcome, outcome == ProviderOutcome.SUCCESS ? "sim_payment" : null,
                outcome == ProviderOutcome.SUCCESS ? null : outcome.name(), outcome == ProviderOutcome.SUCCESS ? null : "Controlled provider failure");
    }

    private static RefundProviderResult refundResult(RefundProviderOutcome outcome) {
        return new RefundProviderResult("SIMULATOR", outcome, outcome == RefundProviderOutcome.SUCCESS ? "sim_refund" : null,
                outcome == RefundProviderOutcome.SUCCESS ? null : outcome.name(), outcome == RefundProviderOutcome.SUCCESS ? null : "Controlled provider failure");
    }

    private record RegisteredEndpoint(String id, String path, String secret) {
        @Override public String toString() { return "RegisteredEndpoint[id=" + id + ", secret=<redacted>]"; }
    }

    private record Receipt(String method, String contentType, String userAgent, String eventId, String signature, byte[] body) {
    }

    private static final class Route {
        private volatile String endpointId;
        private final AtomicInteger responseStatus;
        private final AtomicBoolean timeoutFirst = new AtomicBoolean();
        private final CountDownLatch releaseTimeout = new CountDownLatch(1);
        private final List<Receipt> receipts = new CopyOnWriteArrayList<>();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private Route(int responseStatus) { this.responseStatus = new AtomicInteger(responseStatus); }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TimeConfiguration {
        @Bean
        @Primary
        @DependsOnDatabaseInitialization
        TestClock testClock(JdbcTemplate jdbc) {
            // Clean before the enabled scheduler is instantiated, including in a shared-DB full suite.
            jdbc.execute(CLEAN);
            return new TestClock();
        }
    }

    static final class TestClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(Instant.now().truncatedTo(ChronoUnit.MICROS));
        void set(Instant instant) { now.set(instant); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now.get(), zone); }
        @Override public Instant instant() { return now.get(); }
    }
}
