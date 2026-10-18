package com.flowpay.backend.common.observability;

import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Scope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class DurableTraceContextTest {

    private static final String TRACE_ID = "a".repeat(32);
    private static final String SPAN_ID = "b".repeat(16);

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {
            "", "not-a-context", "00-00000000000000000000000000000000-bbbbbbbbbbbbbbbb-01",
            "00-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-0000000000000000-01",
            "00-AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA-bbbbbbbbbbbbbbbb-01",
            "ff-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-bbbbbbbbbbbbbbbb-01",
            "01-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-bbbbbbbbbbbbbbbb-01",
            "00-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-bbbbbbbbbbbbbbbb-02",
            "00-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-bbbbbbbbbbbbbbbb-01-extra",
            " 00-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-bbbbbbbbbbbbbbbb-01",
            "00-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-bbbbbbbbbbbbbbbb-01\n"
    })
    void shouldDiscardUnsupportedOrMalformedContextWithoutThrowing(String value) {
        assertThat(DurableTraceContext.validatedOrNull(value)).isNull();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldCaptureIdentityAndSamplingOnlyWithoutRetainingScope(boolean sampled) {
        SpanContext source = SpanContext.create(TRACE_ID, SPAN_ID,
                sampled ? TraceFlags.getSampled() : TraceFlags.getDefault(),
                TraceState.builder().put("private", "synthetic-sensitive-state").build());
        try (Scope baggageScope = Baggage.builder().put("private", "synthetic-sensitive-baggage")
                .build().makeCurrent(); Scope spanScope = Span.wrap(source).makeCurrent()) {
            String expected = "00-" + TRACE_ID + "-" + SPAN_ID + (sampled ? "-01" : "-00");
            assertThat(DurableTraceContext.captureOrNull()).isEqualTo(expected);
            assertThat(DurableTraceContext.validatedOrNull(expected)).isEqualTo(expected);
            assertThat(Span.current().getSpanContext()).isEqualTo(source);
        }
        assertThat(DurableTraceContext.captureOrNull()).isNull();
        assertThat(Baggage.current().isEmpty()).isTrue();
    }

    @Test
    void shouldFailOpenWhenCurrentSpanCannotSupplyContext() {
        Span broken = org.mockito.Mockito.mock(Span.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        // Scope attachment itself invokes Boot's MDC hook: fail only the capture,
        // never attach a broken context that would contaminate later tests.
        org.mockito.Mockito.doReturn(SpanContext.getInvalid()).when(broken).getSpanContext();
        try (Scope ignored = io.opentelemetry.context.Context.root().with(broken).makeCurrent()) {
            org.mockito.Mockito.doThrow(new IllegalStateException("synthetic-sensitive-exception"))
                    .when(broken).getSpanContext();
            try {
                assertThat(DurableTraceContext.captureOrNull()).isNull();
            } finally {
                org.mockito.Mockito.doReturn(SpanContext.getInvalid()).when(broken).getSpanContext();
            }
        }
        assertThat(DurableTraceContext.captureOrNull()).isNull();
    }
}
