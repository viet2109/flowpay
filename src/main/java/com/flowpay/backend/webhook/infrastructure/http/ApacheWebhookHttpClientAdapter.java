package com.flowpay.backend.webhook.infrastructure.http;

import com.flowpay.backend.webhook.application.*;
import com.flowpay.backend.common.observability.ExternalCallTracing;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import javax.net.ssl.SSLException;
import java.net.URI;
import java.net.SocketTimeoutException;
import org.apache.hc.core5.http.ConnectionRequestTimeoutException;
import org.apache.hc.client5.http.async.methods.SimpleHttpRequest;
import org.apache.hc.client5.http.async.methods.SimpleRequestProducer;
import org.apache.hc.client5.http.impl.async.CloseableHttpAsyncClient;
import org.apache.hc.core5.concurrent.FutureCallback;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.Method;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import static com.flowpay.backend.webhook.application.WebhookHttpDeliveryResult.Failure.*;

@Component
public class ApacheWebhookHttpClientAdapter implements WebhookHttpClientPort {
    private final CloseableHttpAsyncClient client;
    private final WebhookSigner signer;
    private final WebhookUrlPolicy urlPolicy;
    private final WebhookHttpProperties properties;
    private final ExternalCallTracing tracing;

    // Explicit constructor preserves qualified client injection and required dependencies.
    public ApacheWebhookHttpClientAdapter(@Qualifier(WebhookHttpConfiguration.HTTP_CLIENT) CloseableHttpAsyncClient client,
            WebhookSigner signer, WebhookUrlPolicy urlPolicy, WebhookHttpProperties properties,
            ExternalCallTracing tracing) {
        this.client = Objects.requireNonNull(client);
        this.signer = Objects.requireNonNull(signer);
        this.urlPolicy = Objects.requireNonNull(urlPolicy);
        this.properties = Objects.requireNonNull(properties);
        this.tracing = Objects.requireNonNull(tracing);
    }

    @Override
    public WebhookHttpDeliveryResult send(WebhookHttpDeliveryRequest request) {
        Objects.requireNonNull(request, "Webhook request must not be null");
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Webhook HTTP must run outside a database transaction");
        }
        return tracing.call(ExternalCallTracing.Operation.WEBHOOK_HTTP, () -> sendOutsideTransaction(request),
                result -> result.httpStatus() != null
                        ? ExternalCallTracing.Outcome.valueOf("HTTP_" + result.httpStatus() / 100 + "XX")
                        : ExternalCallTracing.Outcome.valueOf(result.failure().name()));
    }

    private WebhookHttpDeliveryResult sendOutsideTransaction(WebhookHttpDeliveryRequest request) {
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
        var outbound = new SimpleHttpRequest(Method.POST, target);
        outbound.setBody(body, ContentType.APPLICATION_JSON);
        outbound.setHeader("Content-Type", "application/json");
        outbound.setHeader("FlowPay-Signature", signature);
        outbound.setHeader("FlowPay-Event-Id", request.eventPublicId());
        outbound.setHeader("User-Agent", "FlowPay-Webhooks/1.0");
        var headers = new CompletableFuture<Integer>();
        Future<Void> exchange = null;
        try {
            if (System.nanoTime() - started >= properties.requestTimeout().toNanos()) {
                return WebhookHttpDeliveryResult.failed(REQUEST_TIMEOUT, elapsed(started));
            }
            exchange = client.execute(SimpleRequestProducer.create(outbound),
                    new WebhookHeaderResponseConsumer(headers, "https".equalsIgnoreCase(target.getScheme())),
                    new FutureCallback<Void>() {
                        @Override public void completed(Void ignored) { }
                        @Override public void failed(Exception cause) { headers.completeExceptionally(cause); }
                        @Override public void cancelled() { headers.cancel(false); }
                    });
            // Include URL/signing, pool, CONNECT, TLS and header waits in one monotonic deadline.
            long remaining = properties.requestTimeout().toNanos() - (System.nanoTime() - started);
            int status = headers.get(Math.max(0, remaining), TimeUnit.NANOSECONDS);
            return status >= 100 && status <= 599 ? WebhookHttpDeliveryResult.http(status, elapsed(started))
                    : WebhookHttpDeliveryResult.failed(TRANSPORT_FAILURE, elapsed(started));
        } catch (TimeoutException exception) {
            return WebhookHttpDeliveryResult.failed(REQUEST_TIMEOUT, elapsed(started));
        } catch (ExecutionException exception) {
            return WebhookHttpDeliveryResult.failed(classify(exception.getCause()), elapsed(started));
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return WebhookHttpDeliveryResult.failed(TRANSPORT_FAILURE, elapsed(started));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return WebhookHttpDeliveryResult.failed(INTERRUPTED, elapsed(started));
        } finally {
            // Still pending when a body exists. Hard cancellation releases the HTTP/1 connection.
            // Also terminate pool/CONNECT/TLS/header work on timeout or interruption, with no retry.
            if (exchange != null) exchange.cancel(true);
        }
    }

    private static WebhookHttpDeliveryResult.Failure classify(Throwable cause) {
        boolean tls = false;
        for (int depth = 0; cause != null && depth < 8; depth++, cause = cause.getCause()) {
            if (cause instanceof ConnectionRequestTimeoutException) return REQUEST_TIMEOUT;
            // Socket timeouts are used only for connecting/TLS; response waits use the overall deadline.
            if (cause instanceof SocketTimeoutException) return CONNECT_TIMEOUT;
            tls |= cause instanceof SSLException;
        }
        return tls ? TLS_FAILURE : TRANSPORT_FAILURE;
    }

    private static long elapsed(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }
}
