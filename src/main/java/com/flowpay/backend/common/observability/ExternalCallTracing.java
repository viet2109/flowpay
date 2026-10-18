package com.flowpay.backend.common.observability;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.function.Function;
import java.util.function.Supplier;

/** Best-effort scoped client tracing. Never accepts request data or throwables as tags. */
@Component
@RequiredArgsConstructor
public class ExternalCallTracing {

    private final Tracer tracer;

    public enum Operation {
        PAYMENT_PROVIDER("payment.provider"), REFUND_PROVIDER("refund.provider"), WEBHOOK_HTTP("webhook.http");

        private final String spanName;
        Operation(String spanName) { this.spanName = spanName; }
    }

    public enum Outcome {
        SUCCESS, DECLINED, UNKNOWN, TECHNICAL_FAILURE, EXCEPTION,
        HTTP_1XX, HTTP_2XX, HTTP_3XX, HTTP_4XX, HTTP_5XX,
        INVALID_URL, CONNECT_TIMEOUT, REQUEST_TIMEOUT, TLS_FAILURE, TRANSPORT_FAILURE, INTERRUPTED
    }

    public <T> T call(Operation operation, Supplier<T> invocation, Function<T, Outcome> classify) {
        Span span = null;
        Tracer.SpanInScope scope = null;
        try {
            span = tracer.spanBuilder().name(operation.spanName).kind(Span.Kind.CLIENT).start();
            scope = tracer.withSpan(span);
        } catch (RuntimeException ignored) {
            // Telemetry availability never decides whether the business call runs.
        }
        Outcome outcome = Outcome.EXCEPTION;
        try {
            T result = invocation.get();
            try {
                outcome = classify.apply(result);
            } catch (RuntimeException ignored) {
                // Classification failure cannot replace the original result.
            }
            return result;
        } finally {
            if (span != null) {
                try {
                    span.tag("flowpay.outcome", outcome == null ? "EXCEPTION" : outcome.name());
                } catch (RuntimeException ignored) { }
            }
            if (scope != null) {
                try { scope.close(); } catch (RuntimeException ignored) { }
            }
            if (span != null) {
                try { span.end(); } catch (RuntimeException ignored) { }
            }
        }
    }
}
