package com.flowpay.backend.webhook.infrastructure.http;

import org.apache.hc.client5.http.DnsResolver;
import org.junit.jupiter.api.Test;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class FixedWebhookProxyDnsResolverTest {
    @Test
    void onlyProxyResolvesOnceAndAddressSnapshotsCannotBeMutated() throws Exception {
        var source = mock(DnsResolver.class);
        var address = InetAddress.getByAddress(new byte[]{10, 1, 2, 3});
        var input = new InetAddress[]{address};
        when(source.resolve("proxy")).thenReturn(input);
        var fixed = FixedWebhookProxyDnsResolver.snapshot("proxy", Duration.ofSeconds(1), source);
        input[0] = null;
        fixed.resolve("proxy")[0] = null;
        assertThat(fixed.resolve("PROXY")).containsExactly(address);
        assertThat(fixed.resolveCanonicalHostname("proxy")).isEqualTo("proxy");
        assertThatThrownBy(() -> fixed.resolve("merchant.example")).isInstanceOf(UnknownHostException.class);
        assertThatThrownBy(() -> fixed.resolveCanonicalHostname("merchant.example")).isInstanceOf(UnknownHostException.class);
        verify(source, times(1)).resolve("proxy");
        verifyNoMoreInteractions(source);
    }

    @Test
    void failedProxyDnsIsSanitizedAndCannotStartTheClient() throws Exception {
        var source = mock(DnsResolver.class);
        when(source.resolve("proxy")).thenThrow(new UnknownHostException("private operator details"));
        assertThatThrownBy(() -> FixedWebhookProxyDnsResolver.snapshot("proxy", Duration.ofSeconds(1), source))
                .isInstanceOf(IllegalStateException.class).hasMessage("Webhook proxy DNS initialization failed").hasNoCause();
    }

    @Test
    void emptyProxyDnsFailsClosed() throws Exception {
        var source = mock(DnsResolver.class);
        when(source.resolve("proxy")).thenReturn(new InetAddress[0]);
        assertThatThrownBy(() -> FixedWebhookProxyDnsResolver.snapshot("proxy", Duration.ofSeconds(1), source))
                .isInstanceOf(IllegalStateException.class).hasMessage("Webhook proxy DNS initialization failed");
    }

    @Test
    void aTrustedIpv6ProxyAcceptsTheLibrarysBracketNormalizationWithoutAdditionalDns() throws Exception {
        var source = mock(DnsResolver.class);
        var address = InetAddress.getByName("fd00::1"); // Literal only; no external DNS.
        when(source.resolve("[fd00::1]")).thenReturn(new InetAddress[]{address});
        var fixed = FixedWebhookProxyDnsResolver.snapshot("[fd00::1]", Duration.ofSeconds(1), source);
        assertThat(fixed.resolve("fd00::1")).containsExactly(address);
        assertThat(fixed.resolve("[fd00::1]")).containsExactly(address);
        verify(source, times(1)).resolve("[fd00::1]");
    }

    @Test
    void stalledProxyDnsHasABoundedStartupWaitEvenWhenLookupIgnoresInterruption() throws Exception {
        var source = mock(DnsResolver.class);
        var release = new CountDownLatch(1);
        var finished = new CountDownLatch(1);
        when(source.resolve("proxy")).thenAnswer(call -> {
            try {
                while (release.getCount() != 0) {
                    try { release.await(); } catch (InterruptedException ignored) { }
                }
                return new InetAddress[]{InetAddress.getByAddress(new byte[]{10, 1, 2, 3})};
            } finally { finished.countDown(); }
        });
        try {
            long started = System.nanoTime();
            assertThatThrownBy(() -> FixedWebhookProxyDnsResolver.snapshot("proxy", Duration.ofMillis(100), source))
                    .isInstanceOf(IllegalStateException.class).hasMessage("Webhook proxy DNS initialization failed");
            assertThat(System.nanoTime() - started).isLessThan(TimeUnit.SECONDS.toNanos(2));
        } finally {
            release.countDown();
            assertThat(finished.await(2, TimeUnit.SECONDS)).isTrue();
        }
    }
}
