package com.flowpay.backend.webhook.infrastructure.http;

import org.apache.hc.core5.concurrent.FutureCallback;
import org.apache.hc.core5.http.EntityDetails;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.nio.AsyncResponseConsumer;
import org.apache.hc.core5.http.nio.CapacityChannel;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.http.protocol.HttpCoreContext;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Signals final headers separately from exchange completion so unread bodies can be cancelled. */
final class WebhookHeaderResponseConsumer implements AsyncResponseConsumer<Void> {
    private final CompletableFuture<Integer> headers;
    private final boolean requireTls;
    private FutureCallback<Void> completion;

    WebhookHeaderResponseConsumer(CompletableFuture<Integer> headers, boolean requireTls) {
        this.headers = headers;
        this.requireTls = requireTls;
    }

    @Override
    public void consumeResponse(HttpResponse response, EntityDetails entity, HttpContext context,
            FutureCallback<Void> completion) {
        this.completion = completion;
        // Apache forwards a refused CONNECT's plaintext HTTP response to this consumer.
        // It is not a merchant acknowledgement. Fixed HTTP proxy + verified TLS are required for HTTPS.
        if (requireTls && HttpCoreContext.cast(context).getSSLSession() == null) {
            headers.completeExceptionally(new IOException("Webhook HTTPS tunnel not established"));
        } else {
            headers.complete(response.getCode());
        }
        if (entity == null) completion.completed(null);
        // For an entity, do not complete the exchange: the caller cancels it at headers.
    }

    @Override public void informationResponse(HttpResponse response, HttpContext context) { }
    @Override public void updateCapacity(CapacityChannel channel) { } // Never request body bytes.
    @Override public void consume(ByteBuffer source) { source.position(source.limit()); } // Discard any prefetched bytes.
    @Override public void streamEnd(List<? extends Header> trailers) { completion.completed(null); }
    @Override public void failed(Exception cause) { headers.completeExceptionally(cause); }
    @Override public void releaseResources() { } // No body buffer, stream or retained merchant metadata.
}
