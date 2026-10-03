package com.flowpay.backend.webhook.infrastructure.http;

import com.flowpay.backend.webhook.application.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import javax.net.ssl.SSLException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.*;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import static com.flowpay.backend.webhook.application.WebhookHttpDeliveryResult.Failure.*;

@Component
public class JdkWebhookHttpClientAdapter implements WebhookHttpClientPort {
    private final HttpClient client;
    private final WebhookSigner signer;
    private final WebhookUrlPolicy urlPolicy;
    private final WebhookHttpProperties properties;

    // Explicit constructor preserves qualified client injection and fail-closed configuration validation.
    public JdkWebhookHttpClientAdapter(@Qualifier(WebhookHttpConfiguration.HTTP_CLIENT) HttpClient client,
            WebhookSigner signer, WebhookUrlPolicy urlPolicy, WebhookHttpProperties properties) {
        this.client = Objects.requireNonNull(client);
        this.signer = Objects.requireNonNull(signer);
        this.urlPolicy = Objects.requireNonNull(urlPolicy);
        this.properties = Objects.requireNonNull(properties);
        if (client.followRedirects() != HttpClient.Redirect.NEVER
                || !client.connectTimeout().equals(java.util.Optional.of(properties.connectTimeout()))) {
            throw new IllegalArgumentException("Webhook HTTP client requires no redirects and configured connect timeout");
        }
    }

    @Override
    public WebhookHttpDeliveryResult send(WebhookHttpDeliveryRequest request) {
        Objects.requireNonNull(request, "Webhook request must not be null");
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Webhook HTTP must run outside a database transaction");
        }
        long started = System.nanoTime();
        URI target;
        try {
            target = URI.create(urlPolicy.validate(request.url()));
            if (target.getPort() == 0 || target.getPort() > 65535) {
                throw new IllegalArgumentException("Invalid Webhook target port");
            }
        } catch (IllegalArgumentException exception) {
            return WebhookHttpDeliveryResult.failed(INVALID_URL, elapsed(started));
        }
        byte[] body = request.body();
        String signature = signer.signatureHeader(request.unixTimestamp(), request.secret(), body);
        HttpRequest outbound = HttpRequest.newBuilder(target).timeout(properties.requestTimeout())
                .header("Content-Type", "application/json")
                .header("FlowPay-Signature", signature)
                .header("FlowPay-Event-Id", request.eventPublicId())
                .header("User-Agent", "FlowPay-Webhooks/1.0")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        try {
            // Complete at response headers; never drain a merchant's potentially unbounded/slow body.
            HttpResponse<InputStream> response = client.send(outbound, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream ignored = response.body()) {
                // Closing cancels body reception without consuming merchant content.
            } catch (IOException closeFailure) {
                // A known HTTP acknowledgement remains authoritative even if stream cleanup fails.
            }
            int status = response.statusCode();
            return status >= 100 && status <= 599 ? WebhookHttpDeliveryResult.http(status, elapsed(started))
                    : WebhookHttpDeliveryResult.failed(TRANSPORT_FAILURE, elapsed(started));
        } catch (HttpConnectTimeoutException exception) {
            return WebhookHttpDeliveryResult.failed(CONNECT_TIMEOUT, elapsed(started));
        } catch (HttpTimeoutException exception) {
            return WebhookHttpDeliveryResult.failed(REQUEST_TIMEOUT, elapsed(started));
        } catch (SSLException exception) {
            return WebhookHttpDeliveryResult.failed(TLS_FAILURE, elapsed(started));
        } catch (IOException exception) {
            return WebhookHttpDeliveryResult.failed(TRANSPORT_FAILURE, elapsed(started));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return WebhookHttpDeliveryResult.failed(INTERRUPTED, elapsed(started));
        }
    }

    private static long elapsed(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }
}
