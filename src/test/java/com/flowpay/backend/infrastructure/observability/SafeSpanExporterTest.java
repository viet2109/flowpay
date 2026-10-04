package com.flowpay.backend.infrastructure.observability;

import com.flowpay.backend.testing.CapturingSpanExporter;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.*;
import io.opentelemetry.sdk.common.InstrumentationScopeInfo;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SafeSpanExporterTest {
    @Test
    void removesSensitiveNamesAttributesErrorsEventsLinksResourcesAndScopeBeforeExport() {
        String privateValue = "synthetic-private-credential-hash-ciphertext-signature-body";
        var captured = new CapturingSpanExporter();
        try (var sdk = SdkTracerProvider.builder()
                .setResource(Resource.create(Attributes.builder().put("secret", privateValue).build()))
                .addSpanProcessor(SimpleSpanProcessor.create(new SafeSpanExporter(captured))).build()) {
            var span = sdk.get(privateValue, privateValue).spanBuilder(privateValue).setSpanKind(SpanKind.SERVER)
                    .setAttribute("method", "POST").setAttribute("status", "500")
                    .setAttribute("url.full", privateValue).setAttribute("Authorization", privateValue)
                    .addLink(SpanContext.create("a".repeat(32), "b".repeat(16), TraceFlags.getSampled(),
                            TraceState.builder().put("private", privateValue).build()), Attributes.of(
                                    io.opentelemetry.api.common.AttributeKey.stringKey("body"), privateValue)).startSpan();
            span.recordException(new IllegalStateException(privateValue));
            span.addEvent(privateValue);
            span.setStatus(StatusCode.ERROR, privateValue);
            span.end();
            var exported = captured.spans().getFirst();
            assertThat(exported.getName()).isEqualTo("http.server");
            assertThat(exported.getAttributes()).isEqualTo(Attributes.builder()
                    .put("http.request.method", "POST").put("http.response.status_class", "5xx").build());
            assertThat(exported.getEvents()).isEmpty();
            assertThat(exported.getLinks()).isEmpty();
            assertThat(exported.getStatus()).isEqualTo(io.opentelemetry.sdk.trace.data.StatusData.error());
            assertThat(exported.getResource()).isEqualTo(Resource.create(Attributes.builder().put("service.name", "flowpay").build()));
            assertThat(exported.getInstrumentationScopeInfo()).isEqualTo(InstrumentationScopeInfo.create("flowpay"));
        }
    }

    @Test
    void rejectsValuesEvenUnderApprovedKeysAndRemovesRemoteState() {
        var captured = new CapturingSpanExporter();
        try (var sdk = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(new SafeSpanExporter(captured))).build()) {
            var parent = SpanContext.createFromRemoteParent("a".repeat(32), "b".repeat(16), TraceFlags.getSampled(),
                    TraceState.builder().put("private", "synthetic-secret").build());
            var span = sdk.get("fixture").spanBuilder("synthetic-secret")
                    .setParent(io.opentelemetry.context.Context.root().with(Span.wrap(parent)))
                    .setAttribute("method", "synthetic-secret").setAttribute("status", "synthetic-secret")
                    .setAttribute("flowpay.outcome", "synthetic-secret").startSpan();
            span.end();
            var exported = captured.spans().getFirst();
            assertThat(exported.getName()).isEqualTo("internal");
            assertThat(exported.getAttributes()).isEqualTo(Attributes.empty());
            assertThat(exported.getSpanContext().getTraceState().isEmpty()).isTrue();
            assertThat(exported.getParentSpanContext().getTraceState().isEmpty()).isTrue();
            assertThat(exported.getTraceId()).isEqualTo(parent.getTraceId());
            assertThat(exported.getParentSpanId()).isEqualTo(parent.getSpanId());
        }
    }

    @Test
    void throwingExporterReturnsFailureWithoutThrowingOrLoggingItsMessage() {
        var captured = new CapturingSpanExporter();
        captured.unavailable(true);
        assertThat(new SafeSpanExporter(captured).export(java.util.List.of()).isSuccess()).isFalse();
    }
}
