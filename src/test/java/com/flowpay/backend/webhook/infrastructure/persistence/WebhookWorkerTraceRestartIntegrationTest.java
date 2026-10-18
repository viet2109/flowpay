package com.flowpay.backend.webhook.infrastructure.persistence;

import com.flowpay.backend.FlowPayApplication;
import com.flowpay.backend.common.money.Money;
import com.flowpay.backend.common.observability.AsyncWorkTracing;
import com.flowpay.backend.common.observability.DurableTraceContext;
import com.flowpay.backend.testing.CapturingSpanExporter;
import com.flowpay.backend.testing.TraceCaptureConfiguration;
import com.flowpay.backend.webhook.application.*;
import com.flowpay.backend.webhook.domain.*;
import com.sun.net.httpserver.HttpServer;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@Testcontainers(disabledWithoutDocker = true)
class WebhookWorkerTraceRestartIntegrationTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16.15-alpine");
    @Container static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4.3-management-alpine");
    private static final Instant NOW = Instant.parse("2026-10-11T01:02:03Z");
    private static final String PARENT = "00-" + "a".repeat(32) + "-" + "b".repeat(16) + "-01";
    private static final String SECRET = "whsec_synthetic_restart_fixture";

    @Test
    void competingWorkersAndReusedThreadsKeepDistinctDurableContextsAndOneHttpPerClaim() throws Exception {
        try (var app = start(); var pool = Executors.newFixedThreadPool(2)) {
            var jdbc = app.getBean(JdbcTemplate.class);
            long merchant = merchant(jdbc);
            endpoint(app, merchant, "https://merchant.example/webhook");
            materialize(app, merchant, "ievt_parallel_a", "pi_parallel_a", PARENT);
            materialize(app, merchant, "ievt_parallel_c", "pi_parallel_c", PARENT.replace('a', 'c').replace('b', 'd'));
            var parents = new HashMap<String, String>();
            jdbc.query("SELECT public_id, traceparent FROM webhook_events", rs -> {
                parents.put(rs.getString(1), rs.getString(2));
            });
            var calls = new CopyOnWriteArrayList<String>();
            var barrier = new CyclicBarrier(2);
            var worker = worker(app, request -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                String expected = parents.get(request.eventPublicId());
                String current = DurableTraceContext.captureOrNull();
                assertThat(current).startsWith(expected.substring(0, 36)).isNotEqualTo(expected);
                assertThat(org.slf4j.MDC.get("traceId")).isEqualTo(expected.substring(3, 35));
                calls.add(request.eventPublicId());
                try { barrier.await(5, TimeUnit.SECONDS); } catch (Exception failure) { throw new IllegalStateException(failure); }
                return WebhookHttpDeliveryResult.http(204, 1);
            });
            var launch = new CountDownLatch(1);
            Callable<Boolean> run = () -> {
                launch.await();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
                // Candidate discovery is advisory/SKIP LOCKED, so a loser may need another batch.
                do { worker.deliverBatch(); } while (calls.size() < 2 && System.nanoTime() < deadline);
                return cleanThread();
            };
            var a = pool.submit(run);
            var b = pool.submit(run);
            launch.countDown();
            assertThat(a.get(10, TimeUnit.SECONDS)).isTrue();
            assertThat(b.get(10, TimeUnit.SECONDS)).isTrue();
            assertThat(calls).containsExactlyInAnyOrderElementsOf(parents.keySet());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM webhook_deliveries WHERE status = 'DELIVERED'", Long.class)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM webhook_delivery_attempts", Long.class)).isEqualTo(2);
            var cleanA = pool.submit(() -> { worker.deliverBatch(); return cleanThread(); });
            var cleanB = pool.submit(() -> { worker.deliverBatch(); return cleanThread(); });
            assertThat(cleanA.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(cleanB.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(calls).hasSize(2);
            flush(app);
            var attempts = app.getBean(CapturingSpanExporter.class).spans().stream()
                    .filter(s -> s.getName().equals("webhook.delivery")).toList();
            assertThat(attempts).hasSize(2);
            assertThat(attempts.stream().map(s -> s.getTraceId()).distinct()).hasSize(2);
            for (String parent : parents.values()) {
                assertThat(attempts).anySatisfy(s -> {
                    assertThat(s.getTraceId()).isEqualTo(parent.substring(3, 35));
                    assertThat(s.getParentSpanId()).isEqualTo(parent.substring(36, 52));
                });
            }
        }
    }

    private static boolean cleanThread() {
        return !Span.current().getSpanContext().isValid() && org.slf4j.MDC.get("traceId") == null
                && io.opentelemetry.api.baggage.Baggage.current().isEmpty();
    }

    @Test
    void restartPreservesCausalParentAcrossRecoveryTimeoutRetryAndStaleFinalizationWithExactHmac() throws Exception {
        var requests = new CopyOnWriteArrayList<Received>();
        var count = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try (var handlers = Executors.newFixedThreadPool(2)) {
            server.setExecutor(handlers);
            server.createContext("/webhook", exchange -> {
                byte[] body = exchange.getRequestBody().readAllBytes();
                var headers = new HashMap<String, List<String>>();
                exchange.getRequestHeaders().forEach((key, values) -> headers.put(key.toLowerCase(Locale.ROOT), List.copyOf(values)));
                requests.add(new Received(body, headers));
                try {
                    if (count.incrementAndGet() == 1) Thread.sleep(700);
                    exchange.sendResponseHeaders(204, -1);
                } catch (InterruptedException interrupted) { Thread.currentThread().interrupt();
                } catch (java.io.IOException ignored) { /* Timed-out client has closed the connection. */
                } finally { exchange.close(); }
            });
            server.start();
            long deliveryId;
            String savedParent;
            String publicId;
            byte[] body;
            ClaimedWebhookDelivery abandoned;
            try (var source = start()) {
                var jdbc = source.getBean(JdbcTemplate.class);
                long merchant = merchant(jdbc);
                endpoint(source, merchant, "http://127.0.0.1:" + server.getAddress().getPort() + "/webhook");
                materialize(source, merchant, "ievt_restart", "pi_restart", PARENT);
                var event = source.getBean(WebhookEventRepository.class).findBySourceEventId("ievt_restart").orElseThrow();
                publicId = event.publicId();
                body = event.payload().getBytes(StandardCharsets.UTF_8);
                savedParent = jdbc.queryForObject("SELECT traceparent FROM webhook_events WHERE id = ?", String.class, event.internalId());
                assertThat(savedParent).startsWith("00-" + "a".repeat(32) + "-").isNotEqualTo(PARENT);
                deliveryId = jdbc.queryForObject("SELECT id FROM webhook_deliveries", Long.class);
                long endpointId = jdbc.queryForObject("SELECT webhook_endpoint_id FROM webhook_deliveries", Long.class);
                abandoned = source.getBean(WebhookDeliveryExecutionService.class).claim(deliveryId, endpointId).orElseThrow();
                flush(source);
                assertThat(source.getBean(CapturingSpanExporter.class).spans()).anySatisfy(span -> {
                    assertThat(span.getSpanId()).isEqualTo(savedParent.substring(36, 52));
                    assertThat(span.getParentSpanId()).isEqualTo(PARENT.substring(36, 52));
                });
            }
            // Pool, SDK and application were destroyed; only owned PostgreSQL holds the causal context.
            try (var restarted = start()) {
                var jdbc = restarted.getBean(JdbcTemplate.class);
                var clock = restarted.getBean(MutableClock.class);
                clock.now = NOW.plusSeconds(31);
                var execution = restarted.getBean(WebhookDeliveryExecutionService.class);
                var tracing = restarted.getBean(WebhookWorkerTracing.class);
                var http = restarted.getBean(WebhookHttpClientPort.class);
                var worker = worker(restarted, request -> {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                    assertThat(Span.current().getSpanContext().getTraceId()).isEqualTo("a".repeat(32));
                    return http.send(request);
                });
                worker.deliverBatch();
                assertThat(requests).isEmpty();
                assertThat(jdbc.queryForObject("SELECT last_error FROM webhook_deliveries WHERE id = ?", String.class, deliveryId))
                        .isEqualTo("DELIVERY_LEASE_EXPIRED");
                assertThat(tracing.attempt(deliveryId, () -> execution.finalizeResult(abandoned,
                        WebhookHttpDeliveryResult.http(200, 1)))).isFalse();
                clock.now = nextAttempt(jdbc, deliveryId);
                worker.deliverBatch();
                assertThat(jdbc.queryForObject("SELECT last_error FROM webhook_deliveries WHERE id = ?", String.class, deliveryId))
                        .isEqualTo("REQUEST_TIMEOUT");
                clock.now = nextAttempt(jdbc, deliveryId);
                worker.deliverBatch();
                assertThat(jdbc.queryForObject("SELECT status FROM webhook_deliveries WHERE id = ?", String.class, deliveryId))
                        .isEqualTo("DELIVERED");
                assertThat(jdbc.queryForObject("SELECT count(*) FROM webhook_delivery_attempts WHERE delivery_id = ?", Long.class, deliveryId))
                        .isEqualTo(3);
                assertThat(jdbc.queryForObject("SELECT traceparent FROM webhook_events", String.class)).isEqualTo(savedParent);
                assertThat(jdbc.queryForObject("SELECT payload::text FROM webhook_events", String.class).getBytes(StandardCharsets.UTF_8)).isEqualTo(body);
                await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(requests).hasSize(2));
                for (var received : requests) {
                    assertThat(received.body).isEqualTo(body);
                    assertThat(received.headers).doesNotContainKeys("traceparent", "tracestate", "baggage", "x-request-id");
                    assertThat(received.headers.get("flowpay-event-id")).containsExactly(publicId);
                    String signature = received.headers.get("flowpay-signature").getFirst();
                    long timestamp = Long.parseLong(signature.substring(2, signature.indexOf(',')));
                    assertThat(signature).isEqualTo(restarted.getBean(WebhookSigner.class).signatureHeader(timestamp, SECRET, body));
                }
                flush(restarted);
                var spans = restarted.getBean(CapturingSpanExporter.class).spans();
                var attempts = spans.stream().filter(s -> s.getName().equals("webhook.delivery")).toList();
                var recovery = spans.stream().filter(s -> s.getName().equals("webhook.recovery")).toList();
                assertThat(attempts).hasSize(3);
                assertThat(recovery).hasSize(1);
                assertThat(attempts).extracting(s -> s.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("flowpay.outcome")))
                        .containsExactly("STALE", "APPLIED", "APPLIED");
                for (var span : java.util.stream.Stream.concat(attempts.stream(), recovery.stream()).toList()) {
                    assertThat(span.getTraceId()).isEqualTo(savedParent.substring(3, 35));
                    assertThat(span.getParentSpanId()).isEqualTo(savedParent.substring(36, 52));
                    assertThat(span.getEndEpochNanos() - span.getStartEpochNanos()).isLessThan(Duration.ofSeconds(3).toNanos());
                    assertThat(span.getEvents()).isEmpty();
                    assertThat(span.getLinks()).isEmpty();
                }
                var clients = spans.stream().filter(s -> s.getName().equals("webhook.http")).toList();
                assertThat(clients).hasSize(2).allSatisfy(client ->
                        assertThat(attempts).anySatisfy(attempt -> assertThat(client.getParentSpanId()).isEqualTo(attempt.getSpanId())));
                assertThat(Span.current().getSpanContext().isValid()).isFalse();
                assertThat(org.slf4j.MDC.get("traceId")).isNull();
                assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_transactions", Long.class)).isZero();
                assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_intents", Long.class)).isZero();
            }
        } finally { server.stop(0); }
    }

    private static long merchant(JdbcTemplate jdbc) {
        jdbc.execute("TRUNCATE TABLE merchants RESTART IDENTITY CASCADE");
        return jdbc.queryForObject("""
                INSERT INTO merchants (public_id, name, status, created_at, updated_at)
                VALUES ('mrc_trace_restart', 'Trace Restart', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP) RETURNING id
                """, Long.class);
    }
    private static void endpoint(ConfigurableApplicationContext app, long merchant, String url) {
        new TransactionTemplate(app.getBean(PlatformTransactionManager.class)).executeWithoutResult(status ->
                app.getBean(WebhookEndpointRepository.class).save(WebhookEndpoint.create("wep_restart", merchant, url,
                        app.getBean(WebhookSecretCipher.class).encrypt(SECRET), Set.of(WebhookEventType.PAYMENT_SUCCEEDED), NOW)));
    }
    private static void materialize(ConfigurableApplicationContext app, long merchant, String source, String payment, String parent) {
        app.getBean(AsyncWorkTracing.class).call(AsyncWorkTracing.Operation.WEBHOOK_MATERIALIZE, parent,
                () -> app.getBean(WebhookEventMaterializationService.class).materialize(new MaterializeWebhookEventCommand(
                        source, merchant, WebhookEventType.PAYMENT_SUCCEEDED, payment, null, Money.of(1000, "VND"), null, null, NOW)),
                ignored -> AsyncWorkTracing.Outcome.COMPLETED);
    }
    private static WebhookDeliveryWorker worker(ConfigurableApplicationContext app, WebhookHttpClientPort http) {
        return new WebhookDeliveryWorker(app.getBean(WebhookDeliveryExecutionService.class), http,
                app.getBean(WebhookSecretCipher.class), new WebhookDeliveryWorkerProperties(true, Duration.ofSeconds(1), 10, Duration.ofSeconds(30)),
                app.getBean(Clock.class), app.getBean(WebhookMetrics.class), app.getBean(WebhookWorkerTracing.class));
    }
    private static Instant nextAttempt(JdbcTemplate jdbc, long delivery) {
        return jdbc.queryForObject("SELECT next_attempt_at FROM webhook_deliveries WHERE id = ?",
                (rs, row) -> rs.getTimestamp(1).toInstant(), delivery);
    }
    private static void flush(ConfigurableApplicationContext app) {
        app.getBean(SdkTracerProvider.class).forceFlush().join(5, TimeUnit.SECONDS);
    }
    private static ConfigurableApplicationContext start() {
        return new SpringApplicationBuilder(FlowPayApplication.class, Fixture.class, TraceCaptureConfiguration.class).profiles("test")
                .initializers(app -> app.getEnvironment().getPropertySources().addFirst(new MapPropertySource("owned-worker-restart", Map.ofEntries(
                        Map.entry("server.port", 0), Map.entry("spring.datasource.url", POSTGRES.getJdbcUrl()),
                        Map.entry("spring.datasource.username", POSTGRES.getUsername()), Map.entry("spring.datasource.password", POSTGRES.getPassword()),
                        Map.entry("spring.rabbitmq.host", RABBIT.getHost()), Map.entry("spring.rabbitmq.port", RABBIT.getAmqpPort()),
                        Map.entry("spring.rabbitmq.username", RABBIT.getAdminUsername()), Map.entry("spring.rabbitmq.password", RABBIT.getAdminPassword()),
                        Map.entry("management.otlp.metrics.export.enabled", false), Map.entry("management.tracing.export.otlp.enabled", false),
                        Map.entry("management.opentelemetry.tracing.sampler", "parent-based-always-on"),
                        Map.entry("flowpay.webhook.delivery-worker.batch-size", 1),
                        Map.entry("flowpay.webhook.url-policy.allow-insecure-localhost", true),
                        Map.entry("flowpay.webhook.http.connect-timeout", "300ms"), Map.entry("flowpay.webhook.http.request-timeout", "300ms")
                )))).run();
    }
    @TestConfiguration(proxyBeanMethods = false)
    static class Fixture { @Bean @Primary MutableClock mutableClock() { return new MutableClock(); } }
    static class MutableClock extends Clock {
        volatile Instant now = NOW;
        @Override public Instant instant() { return now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }
    private record Received(byte[] body, Map<String, List<String>> headers) { }
}
