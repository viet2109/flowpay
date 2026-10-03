package com.flowpay.backend.webhook.application;

import com.flowpay.backend.testing.PostgresIntegrationTest;
import com.flowpay.backend.webhook.domain.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** PostgreSQL plus real HTTP: deterministic crash/race barriers, no background worker or external targets. */
@SpringBootTest(properties = {
        "flowpay.webhook.url-policy.allow-insecure-localhost=true",
        "flowpay.webhook.delivery-worker.lease-timeout=5s",
        "flowpay.webhook.http.connect-timeout=1s",
        "flowpay.webhook.http.request-timeout=2s"
})
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class WebhookDeliveryHardeningIntegrationTest extends PostgresIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-04T00:00:00Z");
    private static final String SECRET = "whsec_hardening_fixture";
    @Autowired private WebhookDeliveryExecutionService execution;
    @Autowired private WebhookEndpointManagementService management;
    @Autowired private WebhookEndpointRepository endpoints;
    @Autowired private WebhookEventRepository events;
    @Autowired private WebhookDeliveryRepository deliveries;
    @MockitoSpyBean private WebhookDeliveryAttemptRepository attempts;
    @Autowired private WebhookHttpClientPort http;
    @Autowired private WebhookSecretCipher cipher;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;
    @MockitoBean private Clock clock;
    private final AtomicReference<Instant> now = new AtomicReference<>(NOW);
    private final List<Receipt> receipts = new CopyOnWriteArrayList<>();
    private final CountDownLatch receivedFirst = new CountDownLatch(1);
    private final CountDownLatch releaseFirst = new CountDownLatch(1);
    private HttpServer server;
    private ExecutorService handlers;
    private long merchantId;

    @BeforeEach
    void setUp() throws Exception {
        now.set(NOW);
        when(clock.instant()).thenAnswer(call -> now.get());
        jdbc.execute("TRUNCATE TABLE merchants, users RESTART IDENTITY CASCADE");
        merchantId = jdbc.queryForObject("""
                INSERT INTO merchants (public_id, name, status, created_at, updated_at)
                VALUES ('mrc_hardening', 'Hardening', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP) RETURNING id
                """, Long.class);
        handlers = Executors.newVirtualThreadPerTaskExecutor();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(handlers);
        server.start();
    }

    @AfterEach
    void closeReceiver() {
        releaseFirst.countDown();
        if (server != null) server.stop(0);
        if (handlers != null) handlers.shutdownNow();
    }

    @Test
    void merchantAcceptedButFinalizeRolledBackRecoversAsDuplicateWithStableIdentity(CapturedOutput output) {
        receiver("/accepted", 204, false);
        var delivery = delivery("accepted", "/accepted");
        String encrypted = jdbc.queryForObject("SELECT secret_ciphertext FROM webhook_endpoints WHERE id = ?",
                String.class, delivery.webhookEndpointId());
        // Fail after the real merchant has accepted the request, during result persistence.
        doThrow(new DataAccessResourceFailureException(SECRET + " " + encrypted + " SQL_PRIVATE_MARKER"))
                .doCallRealMethod().when(attempts).complete(any());
        worker().deliverBatch();
        assertThat(receipts).hasSize(1);
        assertThat(load(delivery).status()).isEqualTo(WebhookDeliveryStatus.DELIVERING);
        assertThat(history(delivery).getFirst().finishedAt()).isNull();
        assertThat(output.getAll()).contains("could not be persisted")
                .doesNotContain(SECRET, encrypted, "SQL_PRIVATE_MARKER");

        now.set(load(delivery).leaseExpiresAt());
        worker().deliverBatch();
        assertThat(receipts).hasSize(1); // Recovery schedules, it does not immediately resend.
        assertThat(history(delivery).getFirst().errorMessage()).isEqualTo("DELIVERY_LEASE_EXPIRED");
        assertThat(history(delivery).getFirst().httpStatus()).isNull();
        assertThat(history(delivery).getFirst().durationMs()).isNull();
        now.set(load(delivery).nextAttemptAt());
        worker().deliverBatch();
        assertThat(receipts).hasSize(2);
        assertSameEventAndBody();
        assertSignature(receipts.get(0), SECRET);
        assertSignature(receipts.get(1), SECRET);
        assertThat(load(delivery).status()).isEqualTo(WebhookDeliveryStatus.DELIVERED);
        assertThat(history(delivery)).extracting(WebhookDeliveryAttempt::attemptNo).containsExactly(1, 2);
        assertThat(history(delivery).get(1).httpStatus()).isEqualTo(204);
    }

    @Test
    void oldHttpFailureArrivingAfterRecoveryAndNewSuccessCannotOverwriteEitherAttempt() throws Exception {
        receiver("/late", 500, true);
        var delivery = delivery("late", "/late");
        try (var executor = Executors.newSingleThreadExecutor()) {
            var oldWorker = executor.submit(() -> worker().deliverBatch());
            try {
                assertThat(receivedFirst.await(3, TimeUnit.SECONDS)).isTrue();
                now.set(load(delivery).leaseExpiresAt());
                worker().deliverBatch();
                now.set(load(delivery).nextAttemptAt());
                worker().deliverBatch(); // Second request receives 204 while first still waits.
                assertThat(load(delivery).status()).isEqualTo(WebhookDeliveryStatus.DELIVERED);
                var committedHistory = history(delivery).stream().map(WebhookDeliveryAttempt::finishedAt).toList();
                releaseFirst.countDown();
                oldWorker.get(5, TimeUnit.SECONDS);
                assertThat(load(delivery).lastHttpStatus()).isEqualTo(204);
                assertThat(history(delivery)).extracting(WebhookDeliveryAttempt::finishedAt)
                        .containsExactlyElementsOf(committedHistory);
                assertThat(history(delivery).getFirst().errorMessage()).isEqualTo("DELIVERY_LEASE_EXPIRED");
                assertThat(history(delivery).getFirst().httpStatus()).isNull();
                assertThat(history(delivery).get(1).httpStatus()).isEqualTo(204);
                assertSameEventAndBody();
            } finally { releaseFirst.countDown(); }
        }
    }

    @Test
    void rotationWhileHttpIsInflightUsesOldSignatureThenNewSecretOnRetry(CapturedOutput output) throws Exception {
        receiver("/rotate", 500, true);
        var delivery = delivery("rotate", "/rotate");
        try (var executor = Executors.newSingleThreadExecutor()) {
            var inflight = executor.submit(() -> worker().deliverBatch());
            try {
                assertThat(receivedFirst.await(3, TimeUnit.SECONDS)).isTrue();
                var rotated = management.rotateSecret("mrc_hardening", "wep_rotate");
                assertSignature(receipts.getFirst(), SECRET);
                releaseFirst.countDown();
                inflight.get(5, TimeUnit.SECONDS);
                assertThat(load(delivery).status()).isEqualTo(WebhookDeliveryStatus.RETRYING);
                now.set(load(delivery).nextAttemptAt());
                worker().deliverBatch();
                assertSameEventAndBody();
                assertSignature(receipts.get(1), rotated.secret());
                assertThat(receipts.get(1).signature()).isNotEqualTo(signature(receipts.get(1), SECRET));
                assertThat(load(delivery).status()).isEqualTo(WebhookDeliveryStatus.DELIVERED);
                assertThat(output.getAll()).doesNotContain(SECRET, rotated.secret(),
                        receipts.getFirst().signature(), receipts.get(1).signature());
            } finally { releaseFirst.countDown(); }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {204, 500})
    void disableWhileHttpIsInflightAllowsSuccessButNeverAnotherClaim(int status) throws Exception {
        receiver("/disable", status, true);
        var delivery = delivery("disable", "/disable");
        try (var executor = Executors.newSingleThreadExecutor()) {
            var inflight = executor.submit(() -> worker().deliverBatch());
            try {
                assertThat(receivedFirst.await(3, TimeUnit.SECONDS)).isTrue();
                management.disable("mrc_hardening", "wep_disable");
                assertThat(load(delivery).status()).isEqualTo(WebhookDeliveryStatus.DELIVERING);
                releaseFirst.countDown();
                inflight.get(5, TimeUnit.SECONDS);
                assertThat(load(delivery).status()).isEqualTo(status == 204
                        ? WebhookDeliveryStatus.DELIVERED : WebhookDeliveryStatus.DEAD);
                assertThat(load(delivery).nextAttemptAt()).isNull();
                now.set(NOW.plus(Duration.ofDays(1)));
                worker().deliverBatch();
                assertThat(receipts).hasSize(1);
                assertThat(history(delivery)).hasSize(1);
                assertThat(execution.claim(delivery.internalId(), delivery.webhookEndpointId())).isEmpty();
            } finally { releaseFirst.countDown(); }
        }
    }

    @Test
    void authenticatedCiphertextTamperingNeverSendsOrLeaksAndOtherEndpointStillProgresses(CapturedOutput output) {
        receiver("/corrupt", 204, false);
        receiver("/healthy", 204, false);
        var corrupt = delivery("corrupt", "/corrupt");
        var healthy = delivery("healthy", "/healthy");
        String encrypted = jdbc.queryForObject("SELECT secret_ciphertext FROM webhook_endpoints WHERE id = ?",
                String.class, corrupt.webhookEndpointId());
        String[] parts = encrypted.split(":");
        byte[] bytes = Base64.getUrlDecoder().decode(parts[2]);
        bytes[0] ^= 1; // Well-formed envelope, but an invalid GCM authentication tag.
        String tampered = parts[0] + ":" + parts[1] + ":" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        jdbc.update("UPDATE webhook_endpoints SET secret_ciphertext = ? WHERE id = ?", tampered, corrupt.webhookEndpointId());
        worker().deliverBatch();
        assertThat(receipts).hasSize(1);
        assertThat(receipts.getFirst().eventId()).isEqualTo("evt_healthy");
        assertThat(load(corrupt).status()).isEqualTo(WebhookDeliveryStatus.RETRYING);
        assertThat(load(corrupt).lastError()).isEqualTo("TRANSPORT_FAILURE");
        assertThat(history(corrupt).getFirst().errorMessage()).isEqualTo("TRANSPORT_FAILURE");
        assertThat(history(corrupt).getFirst().httpStatus()).isNull();
        assertThat(load(healthy).status()).isEqualTo(WebhookDeliveryStatus.DELIVERED);
        assertThat(output.getAll()).doesNotContain(SECRET, encrypted, tampered, "AEADBadTagException");
    }

    @ParameterizedTest
    @ValueSource(ints = {429, 500})
    void realHttpFailureSchedulesRetryWithoutBlockingUnrelatedEndpoint(int status) {
        receiver("/failed", status, false);
        receiver("/healthy", 204, false);
        var failed = delivery("failed", "/failed");
        var healthy = delivery("healthy", "/healthy");
        worker().deliverBatch();
        assertThat(load(failed).status()).isEqualTo(WebhookDeliveryStatus.RETRYING);
        assertThat(load(failed).nextAttemptAt()).isBetween(NOW.plusSeconds(10), NOW.plusSeconds(12));
        assertThat(history(failed).getFirst().httpStatus()).isEqualTo(status);
        assertThat(history(failed).getFirst().errorMessage()).isEqualTo("HTTP_STATUS");
        assertThat(load(healthy).status()).isEqualTo(WebhookDeliveryStatus.DELIVERED);
        assertThat(receipts).hasSize(2);
    }

    private void receiver(String path, int firstStatus, boolean blockFirst) {
        var callCount = new java.util.concurrent.atomic.AtomicInteger();
        server.createContext(path, exchange -> {
            int call = callCount.incrementAndGet();
            receipts.add(new Receipt(exchange.getRequestHeaders().getFirst("FlowPay-Event-Id"),
                    exchange.getRequestHeaders().getFirst("FlowPay-Signature"), exchange.getRequestBody().readAllBytes()));
            receivedFirst.countDown();
            try {
                if (blockFirst && call == 1 && !releaseFirst.await(8, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Receiver barrier timed out");
                }
                exchange.sendResponseHeaders(call == 1 ? firstStatus : 204, -1);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally { exchange.close(); }
        });
    }

    private WebhookDelivery delivery(String suffix, String path) {
        return new TransactionTemplate(transactions).execute(status -> {
            var endpoint = endpoints.save(WebhookEndpoint.create("wep_" + suffix, merchantId,
                    "http://127.0.0.1:" + server.getAddress().getPort() + path, cipher.encrypt(SECRET),
                    Set.of(WebhookEventType.PAYMENT_SUCCEEDED), NOW));
            var event = events.tryInsert(WebhookEvent.create("evt_" + suffix, "ievt_" + suffix, merchantId,
                    WebhookEventType.PAYMENT_SUCCEEDED, WebhookResourceType.PAYMENT_INTENT, "pi_" + suffix,
                    "{\"id\":\"evt_" + suffix + "\",\"data\":{\"note\":\"Hoàn tiền 😀\"}}", NOW, NOW)).orElseThrow();
            return deliveries.save(WebhookDelivery.create("wdl_" + suffix, event.internalId(), endpoint.internalId(), NOW));
        });
    }

    private WebhookDeliveryWorker worker() {
        return new WebhookDeliveryWorker(execution, http, cipher,
                new WebhookDeliveryWorkerProperties(true, Duration.ofSeconds(1), 100, Duration.ofSeconds(5)), clock);
    }

    private WebhookDelivery load(WebhookDelivery delivery) {
        return deliveries.findByInternalId(delivery.internalId()).orElseThrow();
    }

    private List<WebhookDeliveryAttempt> history(WebhookDelivery delivery) {
        return attempts.findAllByDeliveryId(delivery.internalId());
    }

    private void assertSameEventAndBody() {
        assertThat(receipts).hasSize(2);
        assertThat(receipts.get(1).eventId()).isEqualTo(receipts.getFirst().eventId());
        assertThat(receipts.get(1).body()).isEqualTo(receipts.getFirst().body());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM webhook_events", Long.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM webhook_deliveries", Long.class)).isOne();
    }

    private static void assertSignature(Receipt receipt, String secret) {
        assertThat(receipt.signature()).isEqualTo(signature(receipt, secret));
    }

    private static String signature(Receipt receipt, String secret) {
        try {
            String prefix = receipt.signature().substring(0, receipt.signature().indexOf(",v1="));
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((prefix.substring(2) + ".").getBytes(StandardCharsets.US_ASCII));
            return prefix + ",v1=" + HexFormat.of().formatHex(mac.doFinal(receipt.body()));
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException("Test HMAC unavailable", exception);
        }
    }

    private record Receipt(String eventId, String signature, byte[] body) {
        @Override public String toString() { return "Receipt[eventId=" + eventId + ", signature/body=<redacted>]"; }
    }
}
