package com.flowpay.backend.webhook.infrastructure.http;

import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Trusted proxy DNS snapshot, never a merchant lookup. Refresh by restarting the client. */
final class FixedWebhookProxyDnsResolver implements DnsResolver {
    private final String host;
    private final InetAddress[] addresses;

    private FixedWebhookProxyDnsResolver(String host, InetAddress[] addresses) {
        this.host = normalize(host);
        this.addresses = addresses.clone();
    }

    static FixedWebhookProxyDnsResolver snapshot(String host, Duration timeout) {
        return snapshot(host, timeout, SystemDefaultDnsResolver.INSTANCE);
    }

    // Explicit test seam: simulate a stalled resolver without real external DNS.
    static FixedWebhookProxyDnsResolver snapshot(String host, Duration timeout, DnsResolver resolver) {
        var executor = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("webhook-proxy-dns").factory());
        var lookup = executor.submit(() -> resolver.resolve(host));
        try {
            InetAddress[] addresses = lookup.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
            if (addresses == null || addresses.length == 0 || java.util.Arrays.stream(addresses).anyMatch(java.util.Objects::isNull)) {
                throw new IllegalStateException("Webhook proxy DNS initialization failed");
            }
            return new FixedWebhookProxyDnsResolver(host, addresses);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Webhook proxy DNS initialization interrupted");
        } catch (ExecutionException | TimeoutException exception) {
            throw new IllegalStateException("Webhook proxy DNS initialization failed");
        } finally {
            lookup.cancel(true);
            // JVM DNS may not honor interruption. Do not block startup on executor.close();
            // this daemon performs only trusted DNS and can never initiate a merchant request.
            executor.shutdownNow();
        }
    }

    @Override
    public InetAddress[] resolve(String requestedHost) throws UnknownHostException {
        if (!host.equalsIgnoreCase(normalize(requestedHost))) throw new UnknownHostException("Webhook direct DNS path refused");
        return addresses.clone();
    }

    @Override
    public String resolveCanonicalHostname(String requestedHost) throws UnknownHostException {
        if (!host.equalsIgnoreCase(normalize(requestedHost))) throw new UnknownHostException("Webhook direct DNS path refused");
        return host;
    }

    private static String normalize(String host) {
        return host != null && host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
    }
}
