package com.flowpay.backend.webhook.infrastructure.http;

import com.flowpay.backend.webhook.application.*;
import com.flowpay.backend.webhook.infrastructure.security.HmacSha256WebhookSigner;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import static org.assertj.core.api.Assertions.*;
import static com.flowpay.backend.webhook.application.WebhookHttpDeliveryResult.Failure.*;

/** Real loopback HTTP/TCP fixtures; no external merchant or new test dependency. */
class WebhookHttpClientIntegrationTest {
    private HttpServer server;
    private ExecutorService handlers;
    private HttpClient client;
    private WebhookHttpClientPort adapter;
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<byte[]> received = new AtomicReference<>();
    private static final byte[] RAW_BODY = "{ \"id\": \"evt_utf8\", \"message\": \"Hoàn tiền 😀\" }\n"
            .getBytes(StandardCharsets.UTF_8);

    @BeforeEach
    void start() throws Exception {
        handlers = Executors.newVirtualThreadPerTaskExecutor();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(handlers);
        server.start();
        configure(Duration.ofSeconds(2), Duration.ofSeconds(5));
    }

    @AfterEach
    void stop() {
        server.stop(0);
        handlers.shutdownNow();
        client.shutdownNow();
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 204, 299, 300, 302, 307, 400, 500, 503})
    void sendsExactSignedUtf8BytesAndHeadersAndTreatsOnly2xxAsSuccess(int status) throws Exception {
        var redirected = new AtomicInteger();
        var headers = new AtomicReference<com.sun.net.httpserver.Headers>();
        server.createContext("/redirected", exchange -> {
            redirected.incrementAndGet();
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.createContext("/webhooks", exchange -> {
            calls.incrementAndGet();
            received.set(exchange.getRequestBody().readAllBytes());
            headers.set(exchange.getRequestHeaders());
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            exchange.getResponseHeaders().set("Location", url("/redirected"));
            byte[] response = "private-merchant-response".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, status == 204 ? -1 : response.length);
            if (status != 204) exchange.getResponseBody().write(response);
            exchange.close();
        });
        var result = adapter.send(request(url("/webhooks")));
        assertThat(result.httpStatus()).isEqualTo(status);
        assertThat(result.successful()).isEqualTo(status >= 200 && status <= 299);
        assertThat(result.durationMs()).isNotNegative();
        assertThat(result.toString()).doesNotContain("private-merchant-response", "whsec_fixture");
        assertThat(calls).hasValue(1);
        assertThat(redirected).hasValue(0);
        assertThat(received.get()).isEqualTo(RAW_BODY);
        assertThat(headers.get().getFirst("Content-Type")).isEqualTo("application/json");
        assertThat(headers.get().getFirst("FlowPay-Event-Id")).isEqualTo("evt_utf8");
        assertThat(headers.get().getFirst("User-Agent")).isEqualTo("FlowPay-Webhooks/1.0");
        Mac independent = Mac.getInstance("HmacSHA256");
        independent.init(new SecretKeySpec("whsec_fixture".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        independent.update("1700000000.".getBytes(StandardCharsets.US_ASCII));
        assertThat(headers.get().getFirst("FlowPay-Signature"))
                .isEqualTo("t=1700000000,v1=" + java.util.HexFormat.of().formatHex(independent.doFinal(received.get())));
    }

    @Test
    void responseHeaderReadIsBoundedByRequestTimeoutWithoutAnAdapterRetry() throws Exception {
        configure(Duration.ofSeconds(1), Duration.ofMillis(1500));
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        server.createContext("/slow", exchange -> {
            calls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            started.countDown();
            waitFor(release);
            exchange.close();
        });
        try (var executor = Executors.newSingleThreadExecutor()) {
            var send = executor.submit(() -> adapter.send(request(url("/slow"))));
            try {
                assertThat(started.await(3, TimeUnit.SECONDS)).isTrue();
                var result = send.get(5, TimeUnit.SECONDS);
                assertThat(result.failure()).isEqualTo(REQUEST_TIMEOUT);
                assertThat(result.httpStatus()).isNull();
                assertThat(calls).hasValue(1);
            } finally { release.countDown(); }
        }
    }

    @Test
    void stalledMerchantResponseBodyIsClosedWithoutWaitingForEof() throws Exception {
        var release = new CountDownLatch(1);
        server.createContext("/stream", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write("private-partial-body".getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
            waitFor(release);
            exchange.close();
        });
        try (var executor = Executors.newSingleThreadExecutor()) {
            var send = executor.submit(() -> adapter.send(request(url("/stream"))));
            try {
                var result = send.get(3, TimeUnit.SECONDS);
                assertThat(result.successful()).isTrue();
                assertThat(release.getCount()).isEqualTo(1);
                assertThat(result.toString()).doesNotContain("private-partial-body");
            } finally { release.countDown(); }
        }
    }

    @Test
    void configuredConnectTimeoutBoundsAStalledTlsHandshake() throws Exception {
        configure(Duration.ofMillis(300), Duration.ofSeconds(3));
        var accepted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var tcp = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            tcp.setSoTimeout(3000); // Keep fixture cleanup bounded even if no client reaches accept().
            var peer = executor.submit(() -> {
                try (var socket = tcp.accept()) {
                    accepted.countDown();
                    waitFor(release); // Deliberately never respond to TLS ClientHello.
                }
                return null;
            });
            try {
                var result = adapter.send(request("https://127.0.0.1:" + tcp.getLocalPort() + "/webhooks"));
                assertThat(accepted.await(3, TimeUnit.SECONDS)).isTrue();
                assertThat(result.failure()).isEqualTo(CONNECT_TIMEOUT);
                assertThat(result.httpStatus()).isNull();
                assertThat(result.durationMs()).isLessThan(2500);
            } finally { release.countDown(); }
            peer.get(3, TimeUnit.SECONDS);
        }
    }

    @Test
    void connectionRefusalProducesSafeTransportFailure() throws Exception {
        int unusedPort;
        try (var unused = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            unusedPort = unused.getLocalPort();
        }
        var result = adapter.send(request("http://127.0.0.1:" + unusedPort + "/webhooks"));
        assertThat(result.failure()).isEqualTo(TRANSPORT_FAILURE);
        assertThat(result.httpStatus()).isNull();
        assertThat(result.toString()).doesNotContain("whsec_fixture", "127.0.0.1", "Hoàn tiền");
    }

    private void configure(Duration connect, Duration request) {
        if (client != null) client.shutdownNow();
        client = HttpClient.newBuilder().connectTimeout(connect).followRedirects(HttpClient.Redirect.NEVER).build();
        // Controlled loopback fixture. Production uses the separately verified DefaultWebhookUrlPolicy.
        adapter = new JdkWebhookHttpClientAdapter(client, new HmacSha256WebhookSigner(), url -> url,
                new WebhookHttpProperties(connect, request));
    }

    private WebhookHttpDeliveryRequest request(String url) {
        return new WebhookHttpDeliveryRequest(url, "evt_utf8", RAW_BODY, "whsec_fixture", 1700000000);
    }

    private String url(String path) { return "http://127.0.0.1:" + server.getAddress().getPort() + path; }
    private static void waitFor(CountDownLatch latch) {
        try { if (!latch.await(8, TimeUnit.SECONDS)) throw new IllegalStateException("Fixture timed out"); }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
    }
}
