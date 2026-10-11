package com.flowpay.backend.webhook.infrastructure.http;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import org.apache.hc.client5.http.impl.DefaultSchemePortResolver;
import org.apache.hc.client5.http.impl.routing.DefaultProxyRoutePlanner;
import org.apache.hc.client5.http.impl.routing.DefaultRoutePlanner;
import org.apache.hc.client5.http.routing.HttpRoutePlanner;
import org.apache.hc.core5.http.HttpHost;

/** Trusted operator configuration, never derived from a merchant URL or JVM proxy defaults. */
@ConfigurationProperties(prefix = "flowpay.webhook.egress")
public record WebhookEgressProperties(String proxyHost, Integer proxyPort) {
    public WebhookEgressProperties {
        if (proxyHost != null || proxyPort != null) {
            if (!validHost(proxyHost) || proxyPort == null || proxyPort < 1 || proxyPort > 65535) {
                throw new IllegalArgumentException("Webhook egress requires a proxy hostname and valid port");
            }
        }
    }

    public boolean configured() {
        return proxyHost != null;
    }

    HttpRoutePlanner routePlanner() {
        // Neither planner consults JVM/system proxies or nonProxyHosts. No fallback.
        return configured() ? new DefaultProxyRoutePlanner(new HttpHost("http", proxyHost, proxyPort))
                : new DefaultRoutePlanner(DefaultSchemePortResolver.INSTANCE);
    }

    public static boolean validHost(String host) {
        if (host == null || host.isBlank() || host.chars().anyMatch(Character::isWhitespace)) {
            return false;
        }
        try {
            URI uri = URI.create("http://" + (host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host));
            return uri.getHost() != null && uri.getPort() == -1 && uri.getRawUserInfo() == null
                    && uri.getRawQuery() == null && uri.getRawFragment() == null
                    && uri.getRawPath().isEmpty();
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
