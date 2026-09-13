package com.flowpay.backend.webhook.infrastructure.url;

import com.flowpay.backend.webhook.application.WebhookUrlPolicy;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;

final class DefaultWebhookUrlPolicy implements WebhookUrlPolicy {

    private static final int URL_MAX_LENGTH = 2048;
    private static final Set<String> KNOWN_METADATA_ADDRESSES = Set.of(
            "169.254.169.254",
            "169.254.170.2",
            "100.100.100.200"
    );

    private final boolean allowInsecureLocalhost;

    DefaultWebhookUrlPolicy(boolean allowInsecureLocalhost) {
        this.allowInsecureLocalhost = allowInsecureLocalhost;
    }

    @Override
    public String validate(String url) {
        if (url == null) {
            throw invalid("url must not be null", null);
        }
        String candidate = url.trim();
        if (candidate.isEmpty()) {
            throw invalid("url must not be blank", null);
        }
        if (candidate.length() > URL_MAX_LENGTH) {
            throw invalid("url must not exceed 2048 characters", null);
        }

        URI uri;
        try {
            uri = new URI(candidate);
        } catch (URISyntaxException exception) {
            throw invalid("url must be a valid URI", exception);
        }
        if (uri.isOpaque() || uri.getScheme() == null) {
            throw invalid("url must be an absolute hierarchical URI", null);
        }
        if (uri.getRawUserInfo() != null) {
            throw invalid("url must not contain user-info", null);
        }
        if (uri.getRawFragment() != null) {
            throw invalid("url must not contain a fragment", null);
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw invalid("url must contain a valid host", null);
        }
        String normalizedHost = normalizeHost(host);
        boolean localhostName = isLocalhostName(normalizedHost);
        InetAddress literalAddress = parseLiteralAddress(normalizedHost);
        boolean loopbackLiteral = literalAddress != null && literalAddress.isLoopbackAddress();
        boolean allowedLocalTarget = allowInsecureLocalhost
                && (localhostName || loopbackLiteral);

        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"https".equals(scheme)
                && !("http".equals(scheme) && allowedLocalTarget)) {
            throw invalid("url must use HTTPS", null);
        }
        if ((localhostName || loopbackLiteral) && !allowedLocalTarget) {
            throw invalid("localhost and loopback webhook targets are not allowed", null);
        }
        if (literalAddress != null
                && isBlockedLiteralAddress(literalAddress, normalizedHost)
                && !allowedLocalTarget) {
            throw invalid("private or non-routable webhook targets are not allowed", null);
        }

        return candidate;
    }

    private static String normalizeHost(String host) {
        String normalized = host.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        while (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static boolean isLocalhostName(String host) {
        return "localhost".equals(host) || host.endsWith(".localhost");
    }

    private static InetAddress parseLiteralAddress(String host) {
        if (host.indexOf(':') >= 0) {
            if (host.indexOf('%') >= 0) {
                throw invalid("scoped IPv6 webhook targets are not allowed", null);
            }
            try {
                return InetAddress.getByName(host);
            } catch (UnknownHostException exception) {
                throw invalid("url contains an invalid IPv6 address", exception);
            }
        }
        if (!isPotentialIpv4Literal(host)) {
            return null;
        }

        String[] octets = host.split("\\.", -1);
        if (octets.length != 4) {
            throw invalid("url contains an invalid IPv4 address", null);
        }
        byte[] address = new byte[4];
        for (int index = 0; index < octets.length; index++) {
            String octet = octets[index];
            if (octet.isEmpty() || (octet.length() > 1 && octet.startsWith("0"))) {
                throw invalid("url contains an invalid IPv4 address", null);
            }
            int value;
            try {
                value = Integer.parseInt(octet);
            } catch (NumberFormatException exception) {
                throw invalid("url contains an invalid IPv4 address", exception);
            }
            if (value > 255) {
                throw invalid("url contains an invalid IPv4 address", null);
            }
            address[index] = (byte) value;
        }
        try {
            return InetAddress.getByAddress(address);
        } catch (UnknownHostException exception) {
            throw new IllegalStateException("Could not parse validated IPv4 address", exception);
        }
    }

    private static boolean isPotentialIpv4Literal(String host) {
        if (host.isEmpty()) {
            return false;
        }
        for (int index = 0; index < host.length(); index++) {
            char character = host.charAt(index);
            if (character != '.' && !Character.isDigit(character)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isBlockedLiteralAddress(InetAddress address, String host) {
        return address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()
                || isUniqueLocalIpv6(address)
                || KNOWN_METADATA_ADDRESSES.contains(host);
    }

    private static boolean isUniqueLocalIpv6(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
    }

    private static IllegalArgumentException invalid(String message, Throwable cause) {
        return new IllegalArgumentException(message, cause);
    }
}
