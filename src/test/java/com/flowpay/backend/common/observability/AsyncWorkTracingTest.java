package com.flowpay.backend.common.observability;

import com.flowpay.backend.testing.CapturingSpanExporter;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.trace.*;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AsyncWorkTracingTest {
    private static final String PARENT = "00-" + "a".repeat(32) + "-" + "b".repeat(16) + "-01";

    @Test
    void attemptsHaveDistinctIdsAndDurableParentWithoutAmbientBaggageAndRestoreScope() {
        var capture = new CapturingSpanExporter();
        try (var provider = provider(capture)) {
            var tracing = new AsyncWorkTracing(OpenTelemetrySdk.builder().setTracerProvider(provider).build());
            var ambient = Span.wrap(SpanContext.create("c".repeat(32), "d".repeat(16),
                    TraceFlags.getSampled(), TraceState.getDefault()));
            try (var outer = Context.root().with(ambient).with(Baggage.builder().put("private", "synthetic").build()).makeCurrent()) {
                for (int i = 0; i < 3; i++) {
                    assertThat(tracing.call(AsyncWorkTracing.Operation.LEDGER_CONSUME, PARENT, () -> {
                        assertThat(Baggage.current().isEmpty()).isTrue();
                        assertThat(Span.current().getSpanContext().getTraceId()).isEqualTo("a".repeat(32));
                        return "original";
                    }, ignored -> AsyncWorkTracing.Outcome.COMPLETED)).isEqualTo("original");
                    assertThat(Span.current().getSpanContext()).isEqualTo(ambient.getSpanContext());
                    assertThat(Baggage.current().getEntryValue("private")).isEqualTo("synthetic");
                }
            }
            assertThat(capture.spans()).hasSize(3).allSatisfy(span -> {
                assertThat(span.getParentSpanId()).isEqualTo("b".repeat(16));
                assertThat(span.getParentSpanContext().isRemote()).isTrue();
                assertThat(span.getKind()).isEqualTo(SpanKind.CONSUMER);
                assertThat(span.getEvents()).isEmpty();
                assertThat(span.getLinks()).isEmpty();
            });
            assertThat(capture.spans().stream().map(s -> s.getSpanId()).distinct()).hasSize(3);
        }
        assertThat(Span.current().getSpanContext().isValid()).isFalse();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"bad", "00-00000000000000000000000000000000-bbbbbbbbbbbbbbbb-01",
            "00-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-0000000000000000-01", "00-AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA-bbbbbbbbbbbbbbbb-01"})
    void invalidMetadataStartsRootInsteadOfAmbientTrace(String parent) {
        var capture = new CapturingSpanExporter();
        try (var provider = provider(capture); var outer = Span.wrap(SpanContext.create("a".repeat(32),
                "b".repeat(16), TraceFlags.getSampled(), TraceState.getDefault())).makeCurrent()) {
            new AsyncWorkTracing(OpenTelemetrySdk.builder().setTracerProvider(provider).build())
                    .call(AsyncWorkTracing.Operation.WEBHOOK_RECOVERY, parent, () -> true,
                            applied -> AsyncWorkTracing.Outcome.APPLIED);
            assertThat(capture.spans().getFirst().getParentSpanContext().isValid()).isFalse();
            assertThat(capture.spans().getFirst().getTraceId()).isNotEqualTo("a".repeat(32));
        }
    }

    @Test
    void rejectsNonStringOversizeAndUnsampledIsRespected() {
        assertThat(DurableTraceContext.remoteParentOrRoot(42)).isSameAs(Context.root());
        assertThat(DurableTraceContext.remoteParentOrRoot("a".repeat(10000))).isSameAs(Context.root());
        var capture = new CapturingSpanExporter();
        try (var provider = provider(capture)) {
            new AsyncWorkTracing(OpenTelemetrySdk.builder().setTracerProvider(provider).build())
                    .call(AsyncWorkTracing.Operation.LEDGER_CONSUME, PARENT.substring(0, 53) + "00", () -> {
                        assertThat(Span.current().getSpanContext().getTraceId()).isEqualTo("a".repeat(32));
                        assertThat(Span.current().getSpanContext().isSampled()).isFalse();
                        return true;
                    }, ignored -> AsyncWorkTracing.Outcome.COMPLETED);
            assertThat(capture.spans()).isEmpty();
        }
    }

    @Test
    void telemetryAndClassifierFailureNeverRepeatWorkOrChangeResultOrException() {
        var broken = mock(OpenTelemetry.class);
        when(broken.getTracer("flowpay")).thenThrow(new IllegalStateException("synthetic-private"));
        var tracing = new AsyncWorkTracing(broken);
        AtomicInteger calls = new AtomicInteger();
        assertThat(tracing.call(AsyncWorkTracing.Operation.OUTBOX_PUBLISH, PARENT,
                () -> calls.incrementAndGet(), ignored -> { throw new IllegalStateException(); })).isOne();
        var original = new IllegalArgumentException("synthetic-private");
        assertThatThrownBy(() -> tracing.call(AsyncWorkTracing.Operation.WEBHOOK_DELIVERY, PARENT,
                () -> { calls.incrementAndGet(); throw original; }, ignored -> AsyncWorkTracing.Outcome.COMPLETED))
                .isSameAs(original);
        assertThat(calls).hasValue(2);
        assertThat(Span.current().getSpanContext().isValid()).isFalse();
    }

    @Test
    void concurrentAndReusedThreadsNeverShareContextEvenAfterBusinessFailure() throws Exception {
        var capture = new CapturingSpanExporter();
        try (var provider = provider(capture); var pool = Executors.newFixedThreadPool(2)) {
            var tracing = new AsyncWorkTracing(OpenTelemetrySdk.builder().setTracerProvider(provider).build());
            var start = new CyclicBarrier(2);
            for (int batch = 0; batch < 2; batch++) {
                var a = pool.submit(() -> isolated(tracing, PARENT, start));
                var b = pool.submit(() -> isolated(tracing, PARENT.replace('a', 'c').replace('b', 'd'), start));
                assertThat(a.get(5, TimeUnit.SECONDS)).isTrue();
                assertThat(b.get(5, TimeUnit.SECONDS)).isTrue();
            }
            assertThat(capture.spans()).hasSize(4);
            assertThat(capture.spans().stream().map(s -> s.getSpanId()).distinct()).hasSize(4);
        }
    }

    private static boolean isolated(AsyncWorkTracing tracing, String parent, CyclicBarrier start) {
        assertThat(Span.current().getSpanContext().isValid()).isFalse();
        assertThatThrownBy(() -> tracing.call(AsyncWorkTracing.Operation.WEBHOOK_DELIVERY, parent, () -> {
            try { start.await(3, TimeUnit.SECONDS); } catch (Exception failure) { throw new IllegalStateException(failure); }
            assertThat(Span.current().getSpanContext().getTraceId()).isEqualTo(parent.substring(3, 35));
            throw new IllegalStateException("synthetic business failure");
        }, ignored -> AsyncWorkTracing.Outcome.COMPLETED)).isInstanceOf(IllegalStateException.class);
        return !Span.current().getSpanContext().isValid() && Baggage.current().isEmpty();
    }

    private static SdkTracerProvider provider(CapturingSpanExporter exporter) {
        return SdkTracerProvider.builder().setSampler(Sampler.parentBased(Sampler.alwaysOn()))
                .addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
    }
}
