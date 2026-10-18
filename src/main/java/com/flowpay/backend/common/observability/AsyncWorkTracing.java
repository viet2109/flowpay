package com.flowpay.backend.common.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.function.Function;
import java.util.function.Supplier;

/** One bounded attempt, explicit durable parent, no exception text or business attributes. */
@Component
@RequiredArgsConstructor
public class AsyncWorkTracing {
    public enum Operation {
        OUTBOX_PUBLISH("outbox.publish", SpanKind.PRODUCER),
        LEDGER_CONSUME("ledger.consume", SpanKind.CONSUMER),
        WEBHOOK_MATERIALIZE("webhook.materialize", SpanKind.CONSUMER),
        WEBHOOK_DELIVERY("webhook.delivery", SpanKind.INTERNAL),
        WEBHOOK_RECOVERY("webhook.recovery", SpanKind.INTERNAL);

        private final String spanName;
        private final SpanKind kind;
        Operation(String spanName, SpanKind kind) { this.spanName = spanName; this.kind = kind; }
        public String spanName() { return spanName; }
    }

    public enum Outcome { COMPLETED, EXCEPTION, APPLIED, STALE, CONFIRMED, NOT_CONFIRMED }

    private final OpenTelemetry telemetry;

    public <T> T call(Operation operation, Object parent, Supplier<T> work, Function<T, Outcome> classify) {
        Context context = DurableTraceContext.remoteParentOrRoot(parent);
        Span span = null;
        Scope scope = null;
        Outcome outcome = Outcome.EXCEPTION;
        try {
            try {
                span = telemetry.getTracer("flowpay").spanBuilder(operation.spanName)
                        .setSpanKind(operation.kind).setParent(context).startSpan();
                context = context.with(span);
            } catch (RuntimeException ignored) {
                // Telemetry failure must not prevent or repeat the business invocation.
            }
            try { scope = context.makeCurrent(); } catch (RuntimeException ignored) { }
            T result = work.get();
            outcome = Outcome.COMPLETED;
            try {
                Outcome classified = classify.apply(result);
                if (classified != null) outcome = classified;
            } catch (RuntimeException ignored) { }
            return result;
        } finally {
            if (span != null) {
                try { span.setAttribute("flowpay.outcome", outcome.name()); } catch (RuntimeException ignored) { }
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
