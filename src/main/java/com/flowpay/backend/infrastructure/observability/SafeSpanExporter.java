package com.flowpay.backend.infrastructure.observability;

import com.flowpay.backend.common.observability.ExternalCallTracing;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.common.InstrumentationScopeInfo;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.data.DelegatingSpanData;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.data.StatusData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import lombok.RequiredArgsConstructor;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/** Deny-by-default boundary around every exporter used by Boot's batch processor. */
@RequiredArgsConstructor
final class SafeSpanExporter implements SpanExporter {

    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS", "TRACE");
    private static final Set<String> CLIENT_NAMES = Set.of("payment.provider", "refund.provider", "webhook.http");
    private static final Set<String> OUTCOMES = java.util.Arrays.stream(ExternalCallTracing.Outcome.values())
            .map(Enum::name).collect(java.util.stream.Collectors.toUnmodifiableSet());
    private final SpanExporter delegate;

    @Override public CompletableResultCode export(Collection<SpanData> spans) {
        try {
            return delegate.export(spans.stream().map(SafeSpanExporter::sanitize).toList());
        } catch (RuntimeException ignored) {
            // Do not let SDK diagnostics log a collector's exception message or headers.
            return CompletableResultCode.ofFailure();
        }
    }

    @Override public CompletableResultCode flush() {
        try { return delegate.flush(); } catch (RuntimeException ignored) { return CompletableResultCode.ofFailure(); }
    }

    @Override public CompletableResultCode shutdown() {
        try { return delegate.shutdown(); } catch (RuntimeException ignored) { return CompletableResultCode.ofFailure(); }
    }

    static SpanData sanitize(SpanData source) {
        var attributes = Attributes.builder();
        String method = source.getAttributes().get(AttributeKey.stringKey("method"));
        if (METHODS.contains(method == null ? "" : method)) attributes.put("http.request.method", method);
        String status = source.getAttributes().get(AttributeKey.stringKey("status"));
        if (status != null && status.matches("[1-5][0-9]{2}")) {
            attributes.put("http.response.status_class", status.charAt(0) + "xx");
        }
        String outcome = source.getAttributes().get(AttributeKey.stringKey("flowpay.outcome"));
        if (OUTCOMES.contains(outcome == null ? "" : outcome)) attributes.put("flowpay.outcome", outcome);
        Attributes safeAttributes = attributes.build();
        String name = source.getKind() == SpanKind.SERVER ? "http.server"
                : CLIENT_NAMES.contains(source.getName()) ? source.getName() : "internal";
        return new DelegatingSpanData(source) {
            @Override public String getName() { return name; }
            @Override public Attributes getAttributes() { return safeAttributes; }
            @Override public int getTotalAttributeCount() { return safeAttributes.size(); }
            @Override public List<EventData> getEvents() { return List.of(); }
            @Override public int getTotalRecordedEvents() { return 0; }
            @Override public List<LinkData> getLinks() { return List.of(); }
            @Override public int getTotalRecordedLinks() { return 0; }
            @Override public StatusData getStatus() { return StatusData.create(source.getStatus().getStatusCode(), ""); }
            @Override public SpanContext getSpanContext() { return withoutState(source.getSpanContext()); }
            @Override public SpanContext getParentSpanContext() { return withoutState(source.getParentSpanContext()); }
            @Override public Resource getResource() {
                return Resource.create(Attributes.builder().put("service.name", "flowpay").build());
            }
            @Override public InstrumentationScopeInfo getInstrumentationScopeInfo() { return InstrumentationScopeInfo.create("flowpay"); }
        };
    }

    private static SpanContext withoutState(SpanContext context) {
        return context.isRemote()
                ? SpanContext.createFromRemoteParent(context.getTraceId(), context.getSpanId(), context.getTraceFlags(), TraceState.getDefault())
                : SpanContext.create(context.getTraceId(), context.getSpanId(), context.getTraceFlags(), TraceState.getDefault());
    }
}
