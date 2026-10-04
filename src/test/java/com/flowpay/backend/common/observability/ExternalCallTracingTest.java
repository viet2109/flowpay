package com.flowpay.backend.common.observability;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;

import static com.flowpay.backend.common.observability.ExternalCallTracing.Operation.PAYMENT_PROVIDER;
import static com.flowpay.backend.common.observability.ExternalCallTracing.Outcome.SUCCESS;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExternalCallTracingTest {
    @Test
    void noOpTracingPreservesResultAndOriginalExceptionIdentity() {
        var tracing = new ExternalCallTracing(Tracer.NOOP);
        Object result = new Object();
        assertThat(tracing.call(PAYMENT_PROVIDER, () -> result, ignored -> SUCCESS)).isSameAs(result);
        var failure = new IllegalStateException("synthetic-private-message");
        assertThatThrownBy(() -> tracing.call(PAYMENT_PROVIDER, () -> { throw failure; }, ignored -> SUCCESS))
                .isSameAs(failure);
    }

    @Test
    void creationFailureCannotSkipInvocation() {
        Tracer tracer = mock(Tracer.class);
        when(tracer.spanBuilder()).thenThrow(new IllegalStateException("synthetic-private-message"));
        assertThat(new ExternalCallTracing(tracer).call(PAYMENT_PROVIDER, () -> "accepted", ignored -> SUCCESS))
                .isEqualTo("accepted");
    }

    @Test
    void tagScopeAndEndFailuresCannotMaskProviderExceptionAndNeverRecordThrowable() {
        Tracer tracer = mock(Tracer.class);
        Span.Builder builder = mock(Span.Builder.class, RETURNS_SELF);
        Span span = mock(Span.class);
        Tracer.SpanInScope scope = mock(Tracer.SpanInScope.class);
        when(tracer.spanBuilder()).thenReturn(builder);
        when(builder.start()).thenReturn(span);
        when(tracer.withSpan(span)).thenReturn(scope);
        when(span.tag(anyString(), anyString())).thenThrow(new IllegalStateException("tag unavailable"));
        doThrow(new IllegalStateException("scope unavailable")).when(scope).close();
        doThrow(new IllegalStateException("end unavailable")).when(span).end();
        var failure = new IllegalStateException("synthetic-private-message");
        assertThatThrownBy(() -> new ExternalCallTracing(tracer).call(PAYMENT_PROVIDER,
                () -> { throw failure; }, ignored -> SUCCESS)).isSameAs(failure);
        verify(scope).close();
        verify(span).end();
        verify(span, never()).error(any());
    }

    @Test
    void classificationFailurePreservesOriginalResult() {
        assertThat(new ExternalCallTracing(Tracer.NOOP).call(PAYMENT_PROVIDER, () -> "accepted",
                ignored -> { throw new IllegalStateException("synthetic-private-message"); })).isEqualTo("accepted");
    }
}
