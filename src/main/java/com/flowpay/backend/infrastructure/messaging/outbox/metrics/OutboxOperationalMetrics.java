package com.flowpay.backend.infrastructure.messaging.outbox.metrics;

import com.flowpay.backend.common.observability.DurableMetricsSampler;
import com.flowpay.backend.common.observability.MetricsRecorder;
import com.flowpay.backend.common.observability.MetricsSamplingProperties;
import io.micrometer.core.instrument.Tags;
import org.springframework.stereotype.Component;

@Component
public class OutboxOperationalMetrics {
    private final OutboxMetricsProjection projection;
    private final DurableMetricsSampler<OutboxMetricsSnapshot> sampler;

    public OutboxOperationalMetrics(OutboxMetricsProjection projection, MetricsRecorder recorder,
                                    MetricsSamplingProperties properties) {
        this.projection = projection;
        this.sampler = new DurableMetricsSampler<>(recorder, properties.fixedDelay(), "OUTBOX");
        recorder.gauge("flowpay.outbox.backlog", Tags.of("status", "PENDING"), "rows", () -> sampler.value(OutboxMetricsSnapshot::pending));
        recorder.gauge("flowpay.outbox.backlog", Tags.of("status", "FAILED"), "rows", () -> sampler.value(OutboxMetricsSnapshot::failed));
        recorder.gauge("flowpay.outbox.oldest.unpublished.age", Tags.empty(), "seconds", () -> sampler.value(OutboxMetricsSnapshot::oldestUnpublishedAgeSeconds));
    }

    public void refresh() { sampler.refresh(projection::snapshot); }
}
