package com.flowpay.backend.webhook.infrastructure.http;

import com.flowpay.backend.common.observability.ExternalCallTracing;
import com.flowpay.backend.webhook.application.*;
import io.micrometer.tracing.Tracer;
import org.apache.hc.core5.http.ConnectionRequestTimeoutException;
import org.apache.hc.client5.http.impl.async.CloseableHttpAsyncClient;
import org.apache.hc.core5.concurrent.FutureCallback;
import org.apache.hc.core5.http.EntityDetails;
import org.apache.hc.core5.http.message.BasicHttpResponse;
import org.apache.hc.core5.http.nio.AsyncRequestProducer;
import org.apache.hc.core5.http.nio.AsyncResponseConsumer;
import org.apache.hc.core5.http.protocol.HttpCoreContext;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.net.ssl.SSLException;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static com.flowpay.backend.webhook.application.WebhookHttpDeliveryResult.Failure.*;

class ApacheWebhookHttpClientAdapterTest {
    private final CloseableHttpAsyncClient client = mock(CloseableHttpAsyncClient.class);
    private final WebhookSigner signer = mock(WebhookSigner.class);
    private final WebhookUrlPolicy policy = mock(WebhookUrlPolicy.class);
    private final Future<Void> exchange = mock(Future.class);
    private final WebhookHttpProperties properties = new WebhookHttpProperties(Duration.ofSeconds(2), Duration.ofSeconds(5));
    private final WebhookHttpDeliveryRequest request = new WebhookHttpDeliveryRequest("https://example.com",
            "evt_one", new byte[]{1}, "whsec_sensitive", 1700000000);

    @Test
    void normalizesTransportExceptionsAndNeverRetriesOrLeaksExceptionDetails() {
        for (var entry : java.util.Map.of(
                new SocketTimeoutException("whsec_sensitive private-url"), CONNECT_TIMEOUT,
                new ConnectionRequestTimeoutException("whsec_sensitive private-url"), REQUEST_TIMEOUT,
                new SSLException("whsec_sensitive private-url"), TLS_FAILURE,
                new IOException("private-url", new SSLException("whsec_sensitive")), TLS_FAILURE,
                new IOException("whsec_sensitive private-url"), TRANSPORT_FAILURE,
                new IllegalArgumentException("whsec_sensitive private-url"), TRANSPORT_FAILURE).entrySet()) {
            reset(client, exchange);
            var adapter = adapter(properties);
            when(client.execute(any(AsyncRequestProducer.class), any(AsyncResponseConsumer.class), any(FutureCallback.class)))
                    .thenAnswer(call -> {
                        FutureCallback<Void> callback = call.getArgument(2);
                        callback.failed(entry.getKey());
                        return exchange;
                    });
            var result = adapter.send(request);
            assertThat(result.failure()).isEqualTo(entry.getValue());
            assertThat(result.httpStatus()).isNull();
            assertThat(result.durationMs()).isNotNegative();
            assertThat(result.toString()).doesNotContain("whsec_sensitive", "private-url");
            verify(client, times(1)).execute(any(AsyncRequestProducer.class), any(AsyncResponseConsumer.class), any(FutureCallback.class));
            verify(exchange).cancel(true);
        }
    }

    @Test
    void preservesThreadInterruptionAndCancelsPendingExchange() {
        var adapter = adapter(properties);
        when(client.execute(any(AsyncRequestProducer.class), any(AsyncResponseConsumer.class), any(FutureCallback.class)))
                .thenReturn(exchange);
        try {
            Thread.currentThread().interrupt();
            assertThat(adapter.send(request).failure()).isEqualTo(INTERRUPTED);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verify(exchange).cancel(true);
        } finally { Thread.interrupted(); }
    }

    @Test
    void overallDeadlineCancelsTheExchangeInsteadOfMerelyStoppingTheWait() {
        var adapter = adapter(new WebhookHttpProperties(Duration.ofMillis(10), Duration.ofMillis(30)));
        when(client.execute(any(AsyncRequestProducer.class), any(AsyncResponseConsumer.class), any(FutureCallback.class)))
                .thenReturn(exchange);
        assertThat(adapter.send(request).failure()).isEqualTo(REQUEST_TIMEOUT);
        verify(exchange).cancel(true);
        verify(client, times(1)).execute(any(AsyncRequestProducer.class), any(AsyncResponseConsumer.class), any(FutureCallback.class));
    }

    @Test
    void rejectsUnsafeUrlsBeforeSigningOrCallingTransport() {
        var adapter = adapter(properties);
        when(policy.validate(request.url())).thenThrow(new IllegalArgumentException("private-url sensitive details"));
        var result = adapter.send(request);
        assertThat(result.failure()).isEqualTo(INVALID_URL);
        assertThat(result.toString()).doesNotContain("private-url", "sensitive details");
        verifyNoInteractions(signer, client);
    }

    @Test
    void activeTransactionFailsClosedBeforeAnyOutboundWork() {
        var adapter = adapter(properties);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> adapter.send(request)).isInstanceOf(IllegalStateException.class)
                    .hasMessage("Webhook HTTP must run outside a database transaction");
            verifyNoInteractions(policy, signer, client);
        } finally { TransactionSynchronizationManager.clear(); }
    }

    @Test
    void cancelsUnreadBodyAndLateCleanupFailureDoesNotLoseKnownHttpSuccess() {
        var adapter = adapter(properties);
        when(client.execute(any(AsyncRequestProducer.class), any(AsyncResponseConsumer.class), any(FutureCallback.class)))
                .thenAnswer(call -> {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                    AsyncResponseConsumer<Void> consumer = call.getArgument(1);
                    consumer.consumeResponse(new BasicHttpResponse(200), mock(EntityDetails.class),
                            tlsContext(), mock(FutureCallback.class));
                    FutureCallback<Void> callback = call.getArgument(2);
                    callback.failed(new IOException("private response body"));
                    return exchange;
                });
        assertThat(adapter.send(request).successful()).isTrue();
        verify(exchange).cancel(true);
    }

    @Test
    void invalidTargetPortIsRejectedBeforeSigningOrSending() {
        var adapter = adapter(properties);
        for (int port : new int[]{0, 70000}) {
            String url = "https://example.com:" + port;
            when(policy.validate(url)).thenReturn(url);
            assertThat(adapter.send(new WebhookHttpDeliveryRequest(url, "evt_one", new byte[]{1}, "secret", 1))
                    .failure()).isEqualTo(INVALID_URL);
        }
        verifyNoInteractions(signer, client);
    }

    @Test
    void invalidRemoteStatusIsNormalizedWithoutReturningUnpersistableMetadata() {
        var adapter = adapter(properties);
        when(client.execute(any(AsyncRequestProducer.class), any(AsyncResponseConsumer.class), any(FutureCallback.class)))
                .thenAnswer(call -> {
                    AsyncResponseConsumer<Void> consumer = call.getArgument(1);
                    consumer.consumeResponse(new BasicHttpResponse(600), null, tlsContext(), mock(FutureCallback.class));
                    return exchange;
                });
        assertThat(adapter.send(request).failure()).isEqualTo(TRANSPORT_FAILURE);
    }

    private ApacheWebhookHttpClientAdapter adapter(WebhookHttpProperties timeouts) {
        when(policy.validate(request.url())).thenReturn(request.url());
        when(signer.signatureHeader(anyLong(), anyString(), any())).thenReturn("t=1700000000,v1=fixture");
        return new ApacheWebhookHttpClientAdapter(client, signer, policy, timeouts, new ExternalCallTracing(Tracer.NOOP));
    }

    private static HttpCoreContext tlsContext() {
        var context = HttpCoreContext.create();
        context.setSSLSession(mock(javax.net.ssl.SSLSession.class));
        return context;
    }
}
