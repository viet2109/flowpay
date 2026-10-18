package com.flowpay.backend.infrastructure.observability;

import io.micrometer.common.KeyValues;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.SpanExporters;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.observation.DefaultServerRequestObservationConvention;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.http.server.observation.ServerRequestObservationConvention;

import java.util.List;

@Configuration(proxyBeanMethods = false)
public class HttpTracingConfiguration {

    @Bean
    SpanExporters safeSpanExporters(ObjectProvider<SpanExporter> exporters) {
        // Reuse Boot's batch processor and installed exporters without promoting runtime dependencies.
        return SpanExporters.of(exporters.orderedStream().map(SafeSpanExporter::new).toList());
    }

    @Bean
    ServerRequestObservationConvention safeHttpObservationConvention() {
        return new DefaultServerRequestObservationConvention() {
            @Override public String getContextualName(ServerRequestObservationContext context) { return "http.server"; }
            @Override public KeyValues getHighCardinalityKeyValues(ServerRequestObservationContext context) {
                return KeyValues.empty();
            }
        };
    }

    @Bean
    ContextPropagators traceparentOnlyPropagators() {
        // The standard parser still validates traceparent; arbitrary tracestate/baggage is not read.
        return ContextPropagators.create(new io.opentelemetry.context.propagation.TextMapPropagator() {
            @Override public List<String> fields() { return List.of("traceparent"); }
            @Override public <C> void inject(io.opentelemetry.context.Context context, C carrier,
                    io.opentelemetry.context.propagation.TextMapSetter<C> setter) {
                W3CTraceContextPropagator.getInstance().inject(context, carrier,
                        (target, key, value) -> { if ("traceparent".equals(key)) setter.set(target, key, value); });
            }
            @Override public <C> io.opentelemetry.context.Context extract(io.opentelemetry.context.Context context,
                    C carrier, TextMapGetter<C> getter) {
                return W3CTraceContextPropagator.getInstance().extract(context, carrier, new TextMapGetter<>() {
                    @Override public Iterable<String> keys(C target) { return List.of("traceparent"); }
                    @Override public String get(C target, String key) {
                        return "traceparent".equals(key) ? getter.get(target, key) : null;
                    }
                });
            }
        });
    }
}
