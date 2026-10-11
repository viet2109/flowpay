package com.flowpay.backend.common.observability;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;

import java.util.regex.Pattern;

/** Bounded, optional technical context; never part of business event facts. */
public final class DurableTraceContext {

    private static final Pattern CANONICAL = Pattern.compile(
            "00-[0-9a-f]{32}-[0-9a-f]{16}-0[01]"
    );
    private static final String ZERO_TRACE_ID = "0".repeat(32);
    private static final String ZERO_SPAN_ID = "0".repeat(16);

    private DurableTraceContext() {
    }

    public static String validatedOrNull(String traceparent) {
        if (traceparent == null || traceparent.length() != 55
                || !CANONICAL.matcher(traceparent).matches()
                || traceparent.regionMatches(3, ZERO_TRACE_ID, 0, 32)
                || traceparent.regionMatches(36, ZERO_SPAN_ID, 0, 16)) {
            return null;
        }
        return traceparent;
    }

    public static String captureOrNull() {
        try {
            SpanContext context = Span.current().getSpanContext();
            if (!context.isValid()) {
                return null;
            }
            return validatedOrNull("00-" + context.getTraceId() + "-"
                    + context.getSpanId() + (context.isSampled() ? "-01" : "-00"));
        } catch (RuntimeException ignored) {
            // Tracing availability must not reject or roll back a financial event.
            return null;
        }
    }

    /** Reject non-string transport values; never inherit ambient baggage or scheduler context. */
    public static Context remoteParentOrRoot(Object value) {
        String parent = value instanceof String text ? validatedOrNull(text) : null;
        if (parent == null) return Context.root();
        SpanContext span = SpanContext.createFromRemoteParent(parent.substring(3, 35),
                parent.substring(36, 52), parent.endsWith("01") ? TraceFlags.getSampled()
                        : TraceFlags.getDefault(), TraceState.getDefault());
        return Context.root().with(Span.wrap(span));
    }
}
