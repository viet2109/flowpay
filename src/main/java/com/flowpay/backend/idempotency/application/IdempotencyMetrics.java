package com.flowpay.backend.idempotency.application;

import com.flowpay.backend.common.observability.MetricsRecorder;
import com.flowpay.backend.idempotency.domain.IdempotencyOperation;
import io.micrometer.core.instrument.Tags;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class IdempotencyMetrics {

    private final MetricsRecorder recorder;

    public void acquired(IdempotencyOperation operation, IdempotencyAcquisitionDecision decision) {
        decision(operation, decision.name());
        if (decision == IdempotencyAcquisitionDecision.NEW) {
            recorder.incrementAfterCommit("flowpay.idempotency.reservations", Tags.of("operation", operation.name()));
        }
    }

    public void lookup(IdempotencyOperation operation, IdempotencyAcquisitionDecision decision) {
        decision(operation, decision == null ? "NOT_FOUND" : decision.name());
    }

    private void decision(IdempotencyOperation operation, String decision) {
        recorder.increment("flowpay.idempotency.decisions", Tags.of("operation", operation.name(), "decision", decision));
    }
}
