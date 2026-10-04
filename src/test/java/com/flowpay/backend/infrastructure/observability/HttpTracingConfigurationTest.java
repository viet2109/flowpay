package com.flowpay.backend.infrastructure.observability;

import com.flowpay.backend.testing.CapturingSpanExporter;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.micrometer.tracing.autoconfigure.MicrometerTracingAutoConfiguration;
import org.springframework.boot.micrometer.tracing.autoconfigure.NoopTracerAutoConfiguration;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.OpenTelemetryTracingAutoConfiguration;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.otlp.OtlpTracingAutoConfiguration;
import org.springframework.boot.opentelemetry.autoconfigure.OpenTelemetrySdkAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;

import java.net.ServerSocket;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class HttpTracingConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(context -> {
                try {
                    for (var source : new YamlPropertySourceLoader().load("baseline", new ClassPathResource("application.yaml"))) {
                        context.getEnvironment().getPropertySources().addLast(source);
                    }
                } catch (java.io.IOException exception) { throw new IllegalStateException("Cannot load test baseline"); }
            })
            .withBean(ObservationRegistry.class, ObservationRegistry::create)
            .withConfiguration(AutoConfigurations.of(OpenTelemetrySdkAutoConfiguration.class,
                    OpenTelemetryTracingAutoConfiguration.class, MicrometerTracingAutoConfiguration.class,
                    NoopTracerAutoConfiguration.class, OtlpTracingAutoConfiguration.class))
            .withUserConfiguration(HttpTracingConfiguration.class);

    @Test
    void defaultConfigHasOneRealSdkAndBridgeButNoNetworkExporterAndFiniteQueueLimits() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(SdkTracerProvider.class).hasSingleBean(Tracer.class);
            assertThat(context.getBean(Tracer.class).getClass().getName()).contains("OtelTracer");
            assertThat(context.getBeansOfType(SpanExporter.class)).isEmpty();
            var limits = context.getBean(io.opentelemetry.sdk.trace.SpanLimits.class);
            assertThat(limits.getMaxNumberOfAttributes()).isEqualTo(16);
            assertThat(limits.getMaxAttributeValueLength()).isEqualTo(128);
            assertThat(limits.getMaxNumberOfEvents()).isZero();
            assertThat(limits.getMaxNumberOfLinks()).isZero();
            assertThat(context.getEnvironment().getProperty("management.opentelemetry.tracing.export.max-queue-size", Integer.class)).isEqualTo(2048);
            assertThat(context.getEnvironment().getProperty("logging.pattern.console"))
                    .contains("requestId=%X{requestId:-}", "traceId=%X{traceId:-}", "spanId=%X{spanId:-}");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "1"})
    void configuredRootRatioControlsExportWithoutDroppingTraceIdentity(String ratio) {
        runner.withPropertyValues("management.tracing.sampling.probability=" + ratio)
                .withBean(CapturingSpanExporter.class).run(context -> {
                    assertThat(context).hasNotFailed();
                    var tracer = context.getBean(Tracer.class);
                    var span = tracer.nextSpan().name("fixture").start();
                    assertThat(span.context().traceId()).matches("[0-9a-f]{32}");
                    span.end();
                    context.getBean(SdkTracerProvider.class).forceFlush().join(3, TimeUnit.SECONDS);
                    assertThat(context.getBean(CapturingSpanExporter.class).spans()).hasSize(ratio.equals("1") ? 1 : 0);
                });
    }

    @Test
    void onlyTraceparentIsExtractedAndInjectedNeverBaggageStateOrB3() {
        var propagator = new HttpTracingConfiguration().traceparentOnlyPropagators().getTextMapPropagator();
        Map<String, String> carrier = Map.of("traceparent", "00-" + "a".repeat(32) + "-" + "b".repeat(16) + "-01",
                "tracestate", "private=synthetic-secret", "baggage", "secret=synthetic-secret", "b3", "synthetic-secret");
        TextMapGetter<Map<String, String>> getter = new TextMapGetter<>() {
            @Override public Iterable<String> keys(Map<String, String> target) { return target.keySet(); }
            @Override public String get(Map<String, String> target, String key) { return target.get(key); }
        };
        var extracted = propagator.extract(Context.root(), carrier, getter);
        assertThat(io.opentelemetry.api.trace.Span.fromContext(extracted).getSpanContext().getTraceId()).isEqualTo("a".repeat(32));
        assertThat(io.opentelemetry.api.trace.Span.fromContext(extracted).getSpanContext().getTraceState().isEmpty()).isTrue();
        assertThat(io.opentelemetry.api.baggage.Baggage.fromContext(extracted).isEmpty()).isTrue();
        var outbound = new java.util.HashMap<String, String>();
        propagator.inject(extracted, outbound, Map::put);
        assertThat(outbound).containsOnlyKeys("traceparent");
        assertThat(propagator.fields()).containsExactly("traceparent");
    }

    @Test
    void missingCollectorConnectionCannotFailSynchronousWorkOrKeepContext() throws Exception {
        int port;
        try (var reserved = new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) { port = reserved.getLocalPort(); }
        runner.withPropertyValues("management.tracing.export.otlp.enabled=true", "management.tracing.sampling.probability=1",
                "management.opentelemetry.tracing.export.otlp.endpoint=http://127.0.0.1:" + port + "/v1/traces")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(SpanExporter.class)).hasSize(1);
                    var tracer = context.getBean(Tracer.class);
                    var span = tracer.nextSpan().name("fixture").start();
                    try (var scope = tracer.withSpan(span)) {
                        assertThat(tracer.currentSpan().context().traceId()).isEqualTo(span.context().traceId());
                    } finally { span.end(); }
                    context.getBean(SdkTracerProvider.class).forceFlush().join(5, TimeUnit.SECONDS);
                    assertThat(tracer.currentSpan()).isNull();
                    assertThat(org.slf4j.MDC.get("traceId")).isNull();
                    assertThat(org.slf4j.MDC.get("spanId")).isNull();
                });
    }
}
