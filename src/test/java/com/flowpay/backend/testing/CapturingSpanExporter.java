package com.flowpay.backend.testing;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** No sdk-testing dependency; captures exactly the data passed across the export boundary. */
public final class CapturingSpanExporter implements SpanExporter {
    private final List<SpanData> spans = new CopyOnWriteArrayList<>();
    private volatile boolean unavailable;
    private final java.util.concurrent.atomic.AtomicInteger failedAttempts = new java.util.concurrent.atomic.AtomicInteger();

    public List<SpanData> spans() { return List.copyOf(spans); }
    public void clear() { spans.clear(); }
    public void unavailable(boolean unavailable) { this.unavailable = unavailable; }
    public int failedAttempts() { return failedAttempts.get(); }
    @Override public CompletableResultCode export(Collection<SpanData> batch) {
        if (unavailable) {
            failedAttempts.incrementAndGet();
            throw new IllegalStateException("synthetic-private-exporter-message");
        }
        spans.addAll(batch);
        return CompletableResultCode.ofSuccess();
    }
    @Override public CompletableResultCode flush() { return CompletableResultCode.ofSuccess(); }
    @Override public CompletableResultCode shutdown() { return CompletableResultCode.ofSuccess(); }
}
