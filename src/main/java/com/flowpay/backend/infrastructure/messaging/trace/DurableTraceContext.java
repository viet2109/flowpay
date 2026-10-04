package com.flowpay.backend.infrastructure.messaging.trace;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;

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
}
