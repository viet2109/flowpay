package com.flowpay.backend;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.flowpay.backend.common.security.MerchantApiPrincipal;
import com.flowpay.backend.idempotency.domain.IdempotencyKey;
import com.flowpay.backend.payment.application.*;
import com.flowpay.backend.payment.domain.ProviderOutcome;
import com.flowpay.backend.refund.application.*;
import com.flowpay.backend.refund.domain.RefundProviderOutcome;
import com.flowpay.backend.refund.domain.RefundReason;
import com.flowpay.backend.testing.CapturingSpanExporter;
import com.flowpay.backend.testing.PostgresIntegrationTest;
import com.flowpay.backend.webhook.application.WebhookHttpClientPort;
import com.flowpay.backend.webhook.application.WebhookHttpDeliveryRequest;
import com.sun.net.httpserver.HttpServer;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ValveBase;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.opentelemetry.tracing.sampler=parent-based-always-on",
        "management.tracing.export.otlp.enabled=true",
        "management.opentelemetry.tracing.export.schedule-delay=100ms",
        "management.otlp.metrics.export.enabled=false", "server.tomcat.threads.max=4",
        "flowpay.webhook.url-policy.allow-insecure-localhost=true"
})
@ActiveProfiles("test")
@Import(HttpTracingIntegrationTest.FixtureConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class HttpTracingIntegrationTest extends PostgresIntegrationTest {
    private static final String REMOTE_TRACE = "a".repeat(32);
    private static final String REMOTE_SPAN = "b".repeat(16);
    private static final String PRIVATE = "synthetic-private-password-key-hash-ciphertext-signature-payload";
    private static final List<Boolean> CLEAN_COMPLETIONS = new CopyOnWriteArrayList<>();
    private static final AtomicInteger ACTIVE_REQUESTS = new AtomicInteger();
    private static final AtomicInteger COLLECTOR_ATTEMPTS = new AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicBoolean PRIVATE_EXPORTED = new java.util.concurrent.atomic.AtomicBoolean();
    private static final AtomicReference<Map<String, List<String>>> MERCHANT_HEADERS = new AtomicReference<>();
    private static final AtomicReference<byte[]> MERCHANT_BODY = new AtomicReference<>();
    private static final HttpServer PEER = ownedPeer();
    private static final MerchantApiPrincipal PRINCIPAL = new MerchantApiPrincipal("mrc_tracing_fixture", "key_tracing_fixture");

    @Value("${local.server.port}") int port;
    @Autowired CapturingSpanExporter captured;
    @Autowired SdkTracerProvider sdk;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean PaymentProviderPort paymentProvider;
    @MockitoBean RefundProviderPort refundProvider;
    private HttpClient client;
    private ListAppender<ILoggingEvent> logs;
    private Logger root;

    @DynamicPropertySource
    static void collector(DynamicPropertyRegistry properties) {
        properties.add("management.opentelemetry.tracing.export.otlp.endpoint",
                () -> "http://127.0.0.1:" + PEER.getAddress().getPort() + "/v1/traces");
    }

    @BeforeEach
    void setUp() {
        sdk.forceFlush().join(3, TimeUnit.SECONDS);
        captured.clear();
        captured.unavailable(false);
        CLEAN_COMPLETIONS.clear();
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        logs = new ListAppender<>();
        logs.start();
        root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        root.addAppender(logs);
        jdbc.update("""
                INSERT INTO merchants (public_id, name, status, created_at, updated_at)
                VALUES ('mrc_tracing_fixture', 'Tracing fixture', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                ON CONFLICT (public_id) DO NOTHING
                """);
        when(paymentProvider.charge(any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(Span.current().getSpanContext().isValid()).isTrue();
            return new PaymentProviderResult("SIMULATOR", ProviderOutcome.SUCCESS, PRIVATE, null, null);
        });
        when(refundProvider.refund(any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(Span.current().getSpanContext().isValid()).isTrue();
            return new RefundProviderResult("SIMULATOR", RefundProviderOutcome.SUCCESS, PRIVATE, null, null);
        });
    }

    @AfterEach
    void tearDown() {
        captured.unavailable(false);
        root.detachAppender(logs);
        logs.stop();
        client.close();
    }

    @AfterAll static void closeOwnedPeer() { PEER.stop(0); }

    @Test
    void sampledHttpKeepsRequestIdDistinctAndExcludesSecretsFromSpansAndLogsIncludingErrors() throws Exception {
        var response = request("ok", "client-request", parent(true));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("X-Request-Id")).contains("client-request");
        assertThat(response.headers().firstValue("Fixture-Trace-Id")).contains(REMOTE_TRACE);
        var error = request("error", "error-request", parent(true));
        assertThat(error.statusCode()).isEqualTo(500);
        assertThat(error.body()).contains("INTERNAL_ERROR", "error-request").doesNotContain(PRIVATE);
        flush();
        var server = captured.spans().stream().filter(span -> span.getKind() == SpanKind.SERVER).toList();
        assertThat(server).hasSize(2).allSatisfy(span -> {
            assertThat(span.getTraceId()).isEqualTo(REMOTE_TRACE);
            assertThat(span.getParentSpanId()).isEqualTo(REMOTE_SPAN);
            assertThat(span.getName()).isEqualTo("http.server");
            assertThat(span.getAttributes().asMap().keySet()).containsExactlyInAnyOrder(
                    AttributeKey.stringKey("http.request.method"), AttributeKey.stringKey("http.response.status_class"));
            assertThat(span.getEvents()).isEmpty();
            assertThat(span.getStatus().getDescription()).isEmpty();
            assertThat(span.getSpanContext().getTraceState().isEmpty()).isTrue();
        });
        assertThat(logs.list).filteredOn(event -> event.getFormattedMessage().equals("Tracing fixture handled"))
                .anySatisfy(event -> {
                    assertThat(event.getMDCPropertyMap()).containsEntry("requestId", "client-request")
                            .containsEntry("traceId", REMOTE_TRACE);
                    assertThat(event.getMDCPropertyMap().get("spanId")).matches("[0-9a-f]{16}").isNotEqualTo(REMOTE_SPAN);
                });
        assertNoSecrets();
        assertCleanCompletions(2);
    }

    @Test
    void unsampledParentDoesNotExportAndMalformedContextStartsIndependentRoot() throws Exception {
        var unsampled = request("ok", "unsampled-request", parent(false));
        assertThat(unsampled.statusCode()).isEqualTo(200);
        assertThat(unsampled.headers().firstValue("Fixture-Trace-Id")).contains(REMOTE_TRACE);
        assertThat(unsampled.headers().firstValue("Fixture-Sampled")).contains("false");
        flush();
        assertThat(captured.spans()).isEmpty();
        var malformed = request("ok", "bad request id", "malformed-context");
        assertThat(malformed.headers().firstValue("X-Request-Id").orElseThrow()).startsWith("req_");
        flush();
        var server = captured.spans().stream().filter(span -> span.getKind() == SpanKind.SERVER).toList();
        assertThat(server).hasSize(1);
        assertThat(server.getFirst().getTraceId()).isNotEqualTo(REMOTE_TRACE).matches("[0-9a-f]{32}");
        assertThat(server.getFirst().getParentSpanContext().isValid()).isFalse();
        assertCleanCompletions(2);
    }

    @Test
    void concurrentRequestsDoNotLeakTraceOrMdcOnReusedTomcatThreads() throws Exception {
        try (var callers = Executors.newFixedThreadPool(8)) {
            var responses = java.util.stream.IntStream.range(0, 24).mapToObj(index -> callers.submit(() -> {
                String trace = String.format("%032x", index + 1);
                var response = request("ok", "request-" + index, "00-" + trace + "-" + REMOTE_SPAN + "-01");
                assertThat(response.headers().firstValue("Fixture-Trace-Id")).contains(trace);
                assertThat(response.headers().firstValue("X-Request-Id")).contains("request-" + index);
                return response;
            })).toList();
            for (var response : responses) assertThat(response.get(15, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
        }
        flush();
        assertThat(captured.spans()).filteredOn(span -> span.getKind() == SpanKind.SERVER).hasSize(24);
        assertThat(captured.spans().stream().map(span -> span.getTraceId()).distinct()).hasSize(24);
        assertCleanCompletions(24);
        assertNoSecrets();
    }

    @Test
    void realFinancialServicesCreateClientChildrenWithoutTransactionOrReplaySideEffects() throws Exception {
        long previousId = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) FROM outbox_events", Long.class);
        var response = request("financial", "financial-request", parent(true));
        assertThat(response.statusCode()).isEqualTo(200);
        flush();
        var server = captured.spans().stream().filter(span -> span.getKind() == SpanKind.SERVER).findFirst().orElseThrow();
        assertThat(jdbc.queryForList("SELECT traceparent FROM outbox_events WHERE id > ? AND aggregate_id IN "
                + "(SELECT public_id FROM payment_intents WHERE merchant_id = "
                + "(SELECT id FROM merchants WHERE public_id = 'mrc_tracing_fixture'))", String.class, previousId))
                .hasSize(2).allSatisfy(parent -> {
                    assertThat(parent).startsWith("00-" + server.getTraceId() + "-").endsWith("-01");
                    String spanId = parent.substring(36, 52);
                    assertThat(spanId.equals(server.getSpanId()) || captured.spans().stream().anyMatch(span ->
                            span.getSpanId().equals(spanId) && hasAncestor(span, server.getSpanId()))).isTrue();
                });
        assertThat(captured.spans()).filteredOn(span -> span.getKind() == SpanKind.CLIENT).hasSize(2)
                .allSatisfy(span -> {
                    assertThat(span.getName()).isIn("payment.provider", "refund.provider");
                    assertThat(hasAncestor(span, server.getSpanId())).isTrue();
                    assertThat(span.getTraceId()).isEqualTo(server.getTraceId());
                    assertThat(span.getAttributes().get(AttributeKey.stringKey("flowpay.outcome"))).isEqualTo("SUCCESS");
                });
        assertCleanCompletions(1);
        assertNoSecrets();
    }

    @Test
    void collectorHttp503AndThrowingExporterCannotChangeCommittedFinancialResults() throws Exception {
        captured.unavailable(true);
        int attempts = COLLECTOR_ATTEMPTS.get();
        int failures = captured.failedAttempts();
        int before = jdbc.queryForObject("SELECT count(*) FROM payment_intents WHERE merchant_id = "
                + "(SELECT id FROM merchants WHERE public_id = 'mrc_tracing_fixture') AND status = 'PARTIALLY_REFUNDED'", Integer.class);
        var response = request("financial", "outage-request", parent(true));
        assertThat(response.statusCode()).isEqualTo(200);
        flush();
        await().atMost(Duration.ofSeconds(5)).until(() -> captured.failedAttempts() > failures);
        await().atMost(Duration.ofSeconds(5)).until(() -> COLLECTOR_ATTEMPTS.get() > attempts);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_intents WHERE merchant_id = "
                + "(SELECT id FROM merchants WHERE public_id = 'mrc_tracing_fixture') AND status = 'PARTIALLY_REFUNDED'", Integer.class))
                .isEqualTo(before + 1);
        assertNoSecrets();
        assertCleanCompletions(1);
    }

    @Test
    void thrownProviderIsClassifiedWithoutThrowableDataAndScopesAreCleaned() throws Exception {
        org.mockito.Mockito.doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            throw new IllegalStateException(PRIVATE);
        }).when(paymentProvider).charge(any());
        var response = request("provider-error", "provider-error-request", parent(true));
        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.body()).contains("INTERNAL_ERROR", "provider-error-request").doesNotContain(PRIVATE);
        flush();
        assertThat(captured.spans()).filteredOn(span -> span.getName().equals("payment.provider")).singleElement()
                .satisfies(span -> {
                    assertThat(span.getAttributes().get(AttributeKey.stringKey("flowpay.outcome"))).isEqualTo("EXCEPTION");
                    assertThat(span.getEvents()).isEmpty();
                    assertThat(span.getStatus().getDescription()).isEmpty();
                });
        assertNoSecrets();
        assertCleanCompletions(1);
    }

    @Test
    void webhookClientSpanNeverInjectsInternalContextOrChangesSignedBody() throws Exception {
        var response = request("webhook", "webhook-request", parent(true));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(MERCHANT_HEADERS.get().keySet()).noneMatch(key -> List.of("traceparent", "tracestate", "baggage", "x-request-id")
                .contains(key.toLowerCase(java.util.Locale.ROOT)));
        assertThat(MERCHANT_BODY.get()).isEqualTo(PRIVATE.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        flush();
        assertThat(captured.spans()).filteredOn(span -> span.getName().equals("webhook.http")).singleElement()
                .satisfies(span -> {
                    assertThat(span.getKind()).isEqualTo(SpanKind.CLIENT);
                    assertThat(span.getTraceId()).isEqualTo(REMOTE_TRACE);
                    assertThat(span.getAttributes().get(AttributeKey.stringKey("flowpay.outcome"))).isEqualTo("HTTP_2XX");
                });
        assertNoSecrets();
        assertCleanCompletions(1);
    }

    private HttpResponse<String> request(String mode, String requestId, String traceparent) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/_tracing/" + mode + "?private=" + PRIVATE))
                .timeout(Duration.ofSeconds(10)).header("X-Request-Id", requestId).header("traceparent", traceparent)
                .header("tracestate", "private=" + PRIVATE).header("baggage", "private=" + PRIVATE)
                .header("Authorization", PRIVATE).header("FlowPay-Signature", PRIVATE)
                .GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    private static String parent(boolean sampled) { return "00-" + REMOTE_TRACE + "-" + REMOTE_SPAN + (sampled ? "-01" : "-00"); }
    private void flush() {
        await().atMost(Duration.ofSeconds(3)).until(() -> ACTIVE_REQUESTS.get() == 0);
        sdk.forceFlush().join(5, TimeUnit.SECONDS);
    }
    private boolean hasAncestor(io.opentelemetry.sdk.trace.data.SpanData span, String ancestorId) {
        String parent = span.getParentSpanId();
        for (int depth = 0; depth < 32; depth++) {
            if (parent.equals(ancestorId)) return true;
            String current = parent;
            var next = captured.spans().stream().filter(candidate -> candidate.getSpanId().equals(current)).findFirst();
            if (next.isEmpty()) return false;
            parent = next.get().getParentSpanId();
        }
        return false;
    }
    private void assertCleanCompletions(int count) {
        await().atMost(Duration.ofSeconds(3)).until(() -> CLEAN_COMPLETIONS.size() >= count);
        assertThat(CLEAN_COMPLETIONS).hasSize(count).containsOnly(true);
    }
    private void assertNoSecrets() {
        assertThat(PRIVATE_EXPORTED.get()).isFalse();
        assertThat(captured.spans()).allSatisfy(span -> {
            assertThat(span.getName() + span.getAttributes() + span.getResource() + span.getEvents()
                    + span.getLinks() + span.getStatus()).doesNotContain(PRIVATE, "client-request", "key_tracing_fixture");
        });
        assertThat(logs.list).allSatisfy(event -> {
            assertThat(event.getFormattedMessage() + event.getMDCPropertyMap()).doesNotContain(PRIVATE, "synthetic-private-exporter-message");
            assertThat(event.getThrowableProxy()).isNull();
        });
    }

    private static HttpServer ownedPeer() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/traces", exchange -> {
                byte[] body = exchange.getRequestBody().readAllBytes();
                if (new String(body, java.nio.charset.StandardCharsets.UTF_8).contains(PRIVATE)) PRIVATE_EXPORTED.set(true);
                COLLECTOR_ATTEMPTS.incrementAndGet();
                exchange.sendResponseHeaders(503, -1);
                exchange.close();
            });
            server.createContext("/merchant", exchange -> {
                MERCHANT_HEADERS.set(Map.copyOf(exchange.getRequestHeaders()));
                MERCHANT_BODY.set(exchange.getRequestBody().readAllBytes());
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException exception) { throw new ExceptionInInitializerError("Could not start owned trace fixture"); }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixtureConfiguration {
        @Bean CapturingSpanExporter capturingSpanExporter() { return new CapturingSpanExporter(); }
        @Bean FixtureController fixtureController(IdempotentCreatePaymentService creation,
                IdempotentConfirmPaymentService confirmation, IdempotentRefundService refunds, WebhookHttpClientPort webhook) {
            return new FixtureController(creation, confirmation, refunds, webhook);
        }
        @Bean @Order(-100) SecurityFilterChain fixtureSecurity(HttpSecurity http) throws Exception {
            return http.securityMatcher("/_tracing/**").authorizeHttpRequests(rules -> rules.anyRequest().permitAll()).build();
        }
        @Bean WebServerFactoryCustomizer<TomcatServletWebServerFactory> contextProbe() {
            return factory -> factory.addContextValves(new ValveBase() {
                @Override public void invoke(Request request, Response response) throws IOException, ServletException {
                    ACTIVE_REQUESTS.incrementAndGet();
                    boolean before = cleanContext();
                    try { getNext().invoke(request, response); }
                    finally {
                        CLEAN_COMPLETIONS.add(before && cleanContext());
                        ACTIVE_REQUESTS.decrementAndGet();
                    }
                }
            });
        }
        private static boolean cleanContext() {
            return !Span.fromContext(Context.current()).getSpanContext().isValid()
                    && MDC.get("traceId") == null && MDC.get("spanId") == null && MDC.get("requestId") == null;
        }
    }

    @RestController
    @lombok.RequiredArgsConstructor
    static class FixtureController {
        private static final AtomicInteger OPERATIONS = new AtomicInteger();
        private final IdempotentCreatePaymentService creation;
        private final IdempotentConfirmPaymentService confirmation;
        private final IdempotentRefundService refunds;
        private final WebhookHttpClientPort webhook;

        @GetMapping("/_tracing/{mode}")
        Map<String, String> handle(@PathVariable String mode, HttpServletResponse response) {
            var context = Span.current().getSpanContext();
            response.setHeader("Fixture-Trace-Id", context.getTraceId());
            response.setHeader("Fixture-Span-Id", context.getSpanId());
            response.setHeader("Fixture-Sampled", Boolean.toString(context.isSampled()));
            LoggerFactory.getLogger(FixtureController.class).info("Tracing fixture handled");
            if (mode.equals("error")) throw new IllegalStateException(PRIVATE);
            if (mode.equals("financial") || mode.equals("provider-error")) {
                String key = "trace-" + OPERATIONS.incrementAndGet();
                var payment = creation.create(new IdempotentCreatePaymentCommand(PRINCIPAL, IdempotencyKey.of(key + "create"),
                        1000, "USD", null, PRIVATE)).response().id();
                var confirm = new IdempotentConfirmPaymentCommand(PRINCIPAL, IdempotencyKey.of(key + "confirm"), payment);
                assertThat(confirmation.confirm(confirm).response().paymentStatus())
                        .isEqualTo(com.flowpay.backend.payment.domain.PaymentStatus.SUCCEEDED);
                assertThat(confirmation.confirm(confirm).replayed()).isTrue();
                var refund = new IdempotentRefundCommand(PRINCIPAL, IdempotencyKey.of(key + "refund"), payment, 400, RefundReason.of(PRIVATE));
                assertThat(refunds.create(refund).response().status())
                        .isEqualTo(com.flowpay.backend.refund.domain.RefundStatus.SUCCEEDED);
                assertThat(refunds.create(refund).replayed()).isTrue();
            }
            if (mode.equals("webhook")) assertThat(webhook.send(new WebhookHttpDeliveryRequest(
                    "http://127.0.0.1:" + PEER.getAddress().getPort() + "/merchant?private=" + PRIVATE,
                    "evt_tracing", PRIVATE.getBytes(java.nio.charset.StandardCharsets.UTF_8), PRIVATE, 1700000000)).successful()).isTrue();
            return Map.of("status", "ok");
        }
    }
}
