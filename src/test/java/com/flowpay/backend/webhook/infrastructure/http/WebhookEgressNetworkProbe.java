package com.flowpay.backend.webhook.infrastructure.http;

import com.flowpay.backend.common.observability.ExternalCallTracing;
import com.flowpay.backend.webhook.application.WebhookHttpDeliveryRequest;
import com.flowpay.backend.webhook.application.WebhookHttpDeliveryResult;
import com.flowpay.backend.webhook.application.WebhookUrlPolicy;
import com.flowpay.backend.webhook.infrastructure.security.HmacSha256WebhookSigner;
import io.micrometer.tracing.Tracer;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.apache.hc.client5.http.impl.async.CloseableHttpAsyncClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Executed only by ops/egress/tests/smoke.sh inside an owned Linux network namespace. */
public final class WebhookEgressNetworkProbe {
    private static final byte[] BODY = "{ \"message\": \"fixture UTF-8 €\" }\n".getBytes(StandardCharsets.UTF_8);
    private static final long TIMESTAMP = 1700000000;

    public static void main(String[] arguments) throws Exception {
        System.out.println("Egress fixture JVM: " + Runtime.version());
        // Merchant / JVM nonProxyHosts cannot choose the path of the dedicated client.
        ProxySelector.setDefault(ProxySelector.of(null));
        System.setProperty("http.nonProxyHosts", "*");
        // The old JDK-client bypass flag must have no effect on Apache's explicit TLS strategy.
        System.setProperty("jdk.internal.httpclient.disableHostnameVerification", "true");
        boolean ipv6LiteralCompatible;
        try (var context = context(3128)) {
            var adapter = adapter(context);
            check(adapter.send(request("good.test", "/hook")).successful(), "public IPv4 TLS/HMAC");
            check(adapter.send(request("v6.test", "/hook")).successful(), "public IPv6 TLS/HMAC");
            check(adapter.send(request("11.203.0.10", "/hook")).successful(), "public IPv4 literal TLS");
            ipv6LiteralCompatible = adapter.send(request("[2606:4700:ffff::10]", "/hook")).successful();
            if (!ipv6LiteralCompatible) {
                System.out.println("GATE NOT MET: proxied IPv6 literal TLS; continuing diagnostic cases only");
            }
            for (String host : new String[]{"private.test", "private6.test", "mixed4.test", "mixed6.test",
                    "cross-family.test", "reverse-family.test", "metadata.test", "reserved.test"}) {
                check(adapter.send(request(host, "/hook")).failure() == WebhookHttpDeliveryResult.Failure.TRANSPORT_FAILURE,
                        "private/mixed/reserved DNS denied at CONNECT");
            }
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var first = executor.submit(() -> adapter.send(request("delayed.test", "/hook")));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (count("delayed-a") == 0 || count("delayed-aaaa") == 0) {
                    if (System.nanoTime() > deadline) throw new IllegalStateException("DNS race was not exercised");
                    Thread.sleep(20);
                }
                var second = executor.submit(() -> adapter.send(request("delayed.test", "/hook")));
                check(!first.get(7, TimeUnit.SECONDS).successful() && !second.get(7, TimeUnit.SECONDS).successful(),
                        "concurrent partial A/AAAA cache denied");
            }
            check(adapter.send(request("rebind.test", "/hook")).successful(), "initial rebinding public snapshot");
            Thread.sleep(2200); // Fixture-only DNS TTL expires; use another CONNECT, not a pooled TLS connection.
            try (var fresh = context(3128)) {
                check(!adapter(fresh).send(request("rebind.test", "/hook")).successful(), "rebinding private snapshot denied");
            }
            check(count("rebind-a") >= 2, "rebinding actually re-resolved");
            check(adapter.send(request("wrong.test", "/hook")).failure() == WebhookHttpDeliveryResult.Failure.TLS_FAILURE,
                    "TLS hostname mismatch rejected");
            check(adapter.send(new WebhookHttpDeliveryRequest("https://good.test:8444/hook", "evt_fixture", BODY,
                    "whsec_spike", TIMESTAMP)).failure() == WebhookHttpDeliveryResult.Failure.TLS_FAILURE,
                    "untrusted certificate rejected");
            check(adapter.send(new WebhookHttpDeliveryRequest("https://good.test:8445/hook", "evt_fixture", BODY,
                    "whsec_spike", TIMESTAMP)).successful(), "DNS-only fixture certificate is trusted for its hostname");
            for (String literal : new String[]{"11.203.0.10", "[2606:4700:ffff::10]"}) {
                check(adapter.send(new WebhookHttpDeliveryRequest("https://" + literal + ":8445/hook", "evt_fixture", BODY,
                        "whsec_spike", TIMESTAMP)).failure() == WebhookHttpDeliveryResult.Failure.TLS_FAILURE,
                        "trusted certificate missing IP-SAN rejected");
            }
            check(adapter.send(request("good.test", "/redirect")).httpStatus() == 302, "redirect not followed");
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var stalled = executor.submit(() -> adapter.send(request("good.test", "/stall")));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (count("stalled") == 0) {
                    if (System.nanoTime() > deadline) throw new IllegalStateException("Stalled peer fixture unavailable");
                    Thread.sleep(20);
                }
                check(adapter.send(request("good.test", "/hook")).successful(), "unrelated request progresses");
                var timeout = stalled.get(7, TimeUnit.SECONDS);
                check(timeout.failure() == WebhookHttpDeliveryResult.Failure.REQUEST_TIMEOUT
                        && timeout.durationMs() < 6500, "merchant response bounded at configured five seconds");
            }
            for (String url : new String[]{"http://localhost:8443/hook", "https://172.30.231.9:8443/hook",
                    "https://[fd00:231::9]:8443/hook", "https://good.test:8443/hook#fragment",
                    "https://user:password@good.test:8443/hook"}) {
                check(adapter.send(new WebhookHttpDeliveryRequest(url, "evt_fixture", BODY, "whsec_spike", TIMESTAMP))
                        .failure() == WebhookHttpDeliveryResult.Failure.INVALID_URL, "production URL checks retained");
            }
        }
        try (var outage = context(3129)) {
            var result = adapter(outage).send(request("good.test", "/hook"));
            check(!result.successful() && result.httpStatus() == null && result.durationMs() < 6500,
                    "proxy refusal bounded without direct fallback");
        }
        denyDirect("11.203.0.10");
        denyDirect("2606:4700:ffff::10");
        check(count("private-requests") == 0 && count("invalid-hmac") == 0 && count("unexpected-requests") == 0,
                "blocked/mixed/redirect targets received no signed request; exact HMAC/body retained");
        check(ipv6LiteralCompatible, "public IPv6 literal TLS compatibility (required, not waived)");
        System.out.println("PASS: production Apache adapter DNS/TLS/HMAC/timeouts/proxy and IPv4/IPv6 bypass gate");
    }

    private static AnnotationConfigApplicationContext context(int proxyPort) throws Exception {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().setActiveProfiles("prod");
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("owned-egress-fixture", Map.of(
                "flowpay.webhook.egress.proxy-host", "proxy", "flowpay.webhook.egress.proxy-port", proxyPort,
                "flowpay.webhook.http.connect-timeout", "2s", "flowpay.webhook.http.request-timeout", "5s",
                "flowpay.webhook.url-policy.allow-insecure-localhost", "false")));
        context.register(WebhookHttpConfiguration.class,
                Class.forName("com.flowpay.backend.webhook.infrastructure.url.WebhookUrlPolicyConfiguration"));
        context.refresh();
        return context;
    }

    private static ApacheWebhookHttpClientAdapter adapter(AnnotationConfigApplicationContext context) {
        return new ApacheWebhookHttpClientAdapter(context.getBean(WebhookHttpConfiguration.HTTP_CLIENT, CloseableHttpAsyncClient.class),
                new HmacSha256WebhookSigner(), context.getBean(WebhookUrlPolicy.class),
                context.getBean(WebhookHttpProperties.class), new ExternalCallTracing(Tracer.NOOP));
    }

    private static WebhookHttpDeliveryRequest request(String host, String path) {
        return new WebhookHttpDeliveryRequest("https://" + host + ":8443" + path, "evt_fixture", BODY, "whsec_spike", TIMESTAMP);
    }

    private static int count(String counter) throws Exception {
        // Test control plane only; private DB/broker-like connectivity is intentionally separate from merchant HTTP.
        try (var control = HttpClient.newBuilder().proxy(ProxySelector.of(null)).connectTimeout(Duration.ofSeconds(1)).build()) {
            var result = control.send(HttpRequest.newBuilder(URI.create("http://fixtures:8080/count/" + counter))
                    .timeout(Duration.ofSeconds(2)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (result.statusCode() != 200) throw new IllegalStateException("Fixture control unavailable");
            return Integer.parseInt(result.body());
        }
    }

    private static void denyDirect(String address) throws IOException {
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress(address, 8443), 1000);
            throw new IllegalStateException("Direct egress bypass was reachable");
        } catch (java.net.SocketTimeoutException | java.net.SocketException expected) {
            System.out.println("PASS: direct network bypass denied");
        }
    }

    private static void check(boolean condition, String name) {
        if (!condition) throw new IllegalStateException("Egress acceptance failed: " + name);
        System.out.println("PASS: " + name);
    }
}
