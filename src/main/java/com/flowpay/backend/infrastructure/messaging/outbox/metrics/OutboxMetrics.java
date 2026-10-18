package com.flowpay.backend.infrastructure.messaging.outbox.metrics;

import com.flowpay.backend.common.observability.MetricsRecorder;
import com.flowpay.backend.infrastructure.messaging.IntegrationEventPublicationStatus;
import io.micrometer.core.instrument.Tags;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OutboxMetrics {
    private final MetricsRecorder recorder;

    public void attempt(IntegrationEventPublicationStatus status) {
        recorder.increment("flowpay.outbox.publication.attempts", Tags.of("outcome", status == null ? "EXCEPTION" : status.name()));
    }

    public void published() {
        recorder.incrementAfterCommit("flowpay.outbox.published", Tags.empty());
    }
}
