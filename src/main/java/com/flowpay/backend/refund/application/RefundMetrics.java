package com.flowpay.backend.refund.application;

import com.flowpay.backend.common.observability.MetricsRecorder;
import com.flowpay.backend.refund.domain.RefundProviderOutcome;
import io.micrometer.core.instrument.Tags;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

@Component
@RequiredArgsConstructor
public class RefundMetrics {

    private final MetricsRecorder recorder;

    public void outcomeCommitted(RefundProviderOutcome outcome) {
        recorder.incrementAfterCommit("flowpay.refund.outcomes", Tags.of("outcome", outcome.name()));
    }

    public RefundProviderResult measureProvider(Supplier<RefundProviderResult> invocation) {
        long started = System.nanoTime();
        String outcome = "EXCEPTION";
        try {
            RefundProviderResult result = invocation.get();
            if (result != null) {
                outcome = result.outcome().name();
            }
            return result;
        } finally {
            recorder.recordProviderDuration("flowpay.refund.provider.duration", outcome, System.nanoTime() - started);
        }
    }
}
