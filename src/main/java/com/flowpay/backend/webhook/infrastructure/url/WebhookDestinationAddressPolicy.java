package com.flowpay.backend.webhook.infrastructure.url;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/** Conservative public-unicast policy; the enforcing proxy mirrors these prefixes. No DNS lookups here. */
final class WebhookDestinationAddressPolicy {
    private static final List<Range> BLOCKED = List.of(
            range("0.0.0.0", 8), range("10.0.0.0", 8), range("100.64.0.0", 10),
            range("127.0.0.0", 8), range("169.254.0.0", 16), range("172.16.0.0", 12),
            range("192.0.0.0", 24), range("192.0.2.0", 24), range("192.88.99.0", 24),
            range("192.168.0.0", 16), range("198.18.0.0", 15), range("198.51.100.0", 24),
            range("203.0.113.0", 24), range("224.0.0.0", 3),
            // Only current IPv6 global unicast (2000::/3), excluding special-use subranges.
            range("::", 3), range("4000::", 2), range("8000::", 1),
            range("2001::", 23), range("2001:db8::", 32), range("2002::", 16), range("3fff::", 20));

    private WebhookDestinationAddressPolicy() {
    }

    static boolean blocked(InetAddress address) {
        byte[] bytes = address.getAddress();
        return BLOCKED.stream().anyMatch(range -> range.contains(bytes));
    }

    private static Range range(String literal, int prefix) {
        try {
            return new Range(InetAddress.getByName(literal).getAddress(), prefix);
        } catch (UnknownHostException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private record Range(byte[] network, int prefix) {
        boolean contains(byte[] address) {
            if (network.length != address.length) {
                return false;
            }
            for (int bit = 0; bit < prefix; bit++) {
                int mask = 1 << (7 - bit % 8);
                if ((network[bit / 8] & mask) != (address[bit / 8] & mask)) {
                    return false;
                }
            }
            return true;
        }
    }
}
