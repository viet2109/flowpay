package com.flowpay.backend.webhook.infrastructure.http;

import org.apache.hc.core5.concurrent.FutureCallback;
import org.apache.hc.core5.http.EntityDetails;
import org.apache.hc.core5.http.message.BasicHttpResponse;
import org.apache.hc.core5.http.nio.CapacityChannel;
import org.apache.hc.core5.http.protocol.HttpCoreContext;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WebhookHeaderResponseConsumerTest {
    @Test
    void signalsOnlyFinalStatusAndLeavesBodyExchangePendingForCancellation() throws Exception {
        var headers = new CompletableFuture<Integer>();
        var consumer = new WebhookHeaderResponseConsumer(headers, false);
        FutureCallback<Void> completion = mock(FutureCallback.class);
        var capacity = mock(CapacityChannel.class);
        consumer.informationResponse(new BasicHttpResponse(103), HttpCoreContext.create());
        assertThat(headers).isNotDone();
        consumer.consumeResponse(new BasicHttpResponse(200), mock(EntityDetails.class), HttpCoreContext.create(), completion);
        assertThat(headers).isCompletedWithValue(200);
        consumer.updateCapacity(capacity);
        var prefetched = ByteBuffer.wrap(new byte[]{1, 2, 3});
        consumer.consume(prefetched);
        assertThat(prefetched.hasRemaining()).isFalse();
        consumer.failed(new IOException("private-body"));
        consumer.releaseResources();
        assertThat(headers).isCompletedWithValue(200);
        verifyNoInteractions(capacity, completion);
    }

    @Test
    void bodylessResponseAlsoCompletesExchange() {
        var headers = new CompletableFuture<Integer>();
        var consumer = new WebhookHeaderResponseConsumer(headers, false);
        FutureCallback<Void> completion = mock(FutureCallback.class);
        consumer.consumeResponse(new BasicHttpResponse(204), null, HttpCoreContext.create(), completion);
        assertThat(headers).isCompletedWithValue(204);
        verify(completion).completed(null);
    }

    @Test
    void aPlaintextConnectRefusalIsNotAnHttpsMerchantAcknowledgement() {
        var headers = new CompletableFuture<Integer>();
        var consumer = new WebhookHeaderResponseConsumer(headers, true);
        consumer.consumeResponse(new BasicHttpResponse(403), mock(EntityDetails.class),
                HttpCoreContext.create(), mock(FutureCallback.class));
        assertThat(headers).isCompletedExceptionally();
    }
}
