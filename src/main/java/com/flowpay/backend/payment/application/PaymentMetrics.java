package com.flowpay.backend.payment.application;

import com.flowpay.backend.common.observability.MetricsRecorder;
import com.flowpay.backend.payment.domain.ProviderOutcome;
import io.micrometer.core.instrument.Tags;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

@Component
@RequiredArgsConstructor
public class PaymentMetrics {

    private final MetricsRecorder recorder;

    public void outcomeCommitted(ProviderOutcome outcome) {
        recorder.incrementAfterCommit("flowpay.payment.confirmation.outcomes", Tags.of("outcome", outcome.name()));
    }

    public PaymentProviderResult measureProvider(Supplier<PaymentProviderResult> invocation) {
        long started = System.nanoTime();
        String outcome = "EXCEPTION";
        try {
            PaymentProviderResult result = invocation.get();
            if (result != null) {
                outcome = result.outcome().name();
            }
            return result;
        } finally {
            recorder.recordProviderDuration("flowpay.payment.provider.duration", outcome, System.nanoTime() - started);
        }
    }
}
