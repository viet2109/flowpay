package com.flowpay.backend.webhook.infrastructure.http;

import com.flowpay.backend.webhook.application.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import javax.net.ssl.SSLException;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.*;
import java.time.Duration;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static com.flowpay.backend.webhook.application.WebhookHttpDeliveryResult.Failure.*;

class JdkWebhookHttpClientAdapterTest {
    private final HttpClient client = mock(HttpClient.class);
    private final WebhookSigner signer = mock(WebhookSigner.class);
    private final WebhookUrlPolicy policy = mock(WebhookUrlPolicy.class);
    private final WebhookHttpProperties properties = new WebhookHttpProperties(Duration.ofSeconds(2), Duration.ofSeconds(5));
    private final WebhookHttpDeliveryRequest request = new WebhookHttpDeliveryRequest("https://example.com",
            "evt_one", new byte[]{1}, "whsec_sensitive", 1700000000);

    @Test
    void normalizesTransportExceptionsAndNeverRetriesOrLeaksExceptionDetails() throws Exception {
        for (var entry : java.util.Map.of(
                new HttpConnectTimeoutException("whsec_sensitive private-url"), CONNECT_TIMEOUT,
                new HttpTimeoutException("whsec_sensitive private-url"), REQUEST_TIMEOUT,
                new SSLException("whsec_sensitive private-url"), TLS_FAILURE,
                new IOException("whsec_sensitive private-url"), TRANSPORT_FAILURE).entrySet()) {
            reset(client);
            var adapter = adapter();
            when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenThrow(entry.getKey());
            var result = adapter.send(request);
            assertThat(result.failure()).isEqualTo(entry.getValue());
            assertThat(result.httpStatus()).isNull();
            assertThat(result.durationMs()).isNotNegative();
            assertThat(result.toString()).doesNotContain("whsec_sensitive", "private-url");
            verify(client, times(1)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        }
    }

    @Test
    void preservesThreadInterruptionWithoutLeakingMessage() throws Exception {
        var adapter = adapter();
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new InterruptedException("whsec_sensitive"));
        try {
            assertThat(adapter.send(request).failure()).isEqualTo(INTERRUPTED);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
    }

    @Test
    void rejectsUnsafeUrlsBeforeSigningOrCallingTransport() throws Exception {
        var adapter = adapter();
        when(policy.validate(request.url())).thenThrow(new IllegalArgumentException("private-url sensitive details"));
        var result = adapter.send(request);
        assertThat(result.failure()).isEqualTo(INVALID_URL);
        assertThat(result.toString()).doesNotContain("private-url", "sensitive details");
        verifyNoInteractions(signer);
        verify(client, never()).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void activeTransactionFailsClosedBeforeAnyOutboundWork() throws Exception {
        var adapter = adapter();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> adapter.send(request)).isInstanceOf(IllegalStateException.class)
                    .hasMessage("Webhook HTTP must run outside a database transaction");
            verifyNoInteractions(policy, signer);
            verify(client, never()).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        } finally { TransactionSynchronizationManager.clear(); }
    }

    @Test
    void responseBodyIsOnlyClosedNotReadAndCloseFailureDoesNotLoseKnownHttpSuccess() throws Exception {
        var adapter = adapter();
        HttpResponse<InputStream> response = mock(HttpResponse.class);
        InputStream body = mock(InputStream.class);
        when(response.body()).thenReturn(body);
        when(response.statusCode()).thenReturn(200);
        doThrow(new IOException("private response data")).when(body).close();
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            HttpRequest outbound = call.getArgument(0);
            assertThat(outbound.timeout()).contains(Duration.ofSeconds(5));
            return response;
        });
        assertThat(adapter.send(request).successful()).isTrue();
        verify(body).close();
        verifyNoMoreInteractions(body);
    }

    @Test
    void refusesMismatchedTimeoutOrRedirectConfiguration() {
        when(client.followRedirects()).thenReturn(HttpClient.Redirect.ALWAYS);
        assertThatThrownBy(() -> new JdkWebhookHttpClientAdapter(client, signer, policy, properties))
                .isInstanceOf(IllegalArgumentException.class);
        when(client.followRedirects()).thenReturn(HttpClient.Redirect.NEVER);
        when(client.connectTimeout()).thenReturn(Optional.empty());
        assertThatThrownBy(() -> new JdkWebhookHttpClientAdapter(client, signer, policy, properties))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidTargetPortIsRejectedBeforeSigningOrSending() throws Exception {
        var adapter = adapter();
        for (int port : new int[]{0, 70000}) {
            String url = "https://example.com:" + port;
            when(policy.validate(url)).thenReturn(url);
            assertThat(adapter.send(new WebhookHttpDeliveryRequest(url, "evt_one", new byte[]{1}, "secret", 1))
                    .failure()).isEqualTo(INVALID_URL);
        }
        verifyNoInteractions(signer);
        verify(client, never()).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void invalidRemoteStatusIsNormalizedWithoutReturningUnpersistableMetadata() throws Exception {
        var adapter = adapter();
        HttpResponse<InputStream> response = mock(HttpResponse.class);
        when(response.body()).thenReturn(InputStream.nullInputStream());
        when(response.statusCode()).thenReturn(600);
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        assertThat(adapter.send(request).failure()).isEqualTo(TRANSPORT_FAILURE);
    }

    private JdkWebhookHttpClientAdapter adapter() {
        when(client.followRedirects()).thenReturn(HttpClient.Redirect.NEVER);
        when(client.connectTimeout()).thenReturn(Optional.of(properties.connectTimeout()));
        when(policy.validate(request.url())).thenReturn(request.url());
        // A fixture header, not a logged signature or a real credential.
        when(signer.signatureHeader(anyLong(), anyString(), any())).thenReturn("t=1700000000,v1=fixture");
        return new JdkWebhookHttpClientAdapter(client, signer, policy, properties);
    }
}
