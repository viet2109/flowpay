package com.flowpay.backend.webhook.infrastructure.metrics;

import com.flowpay.backend.common.observability.DurableMetricsSampler;
import com.flowpay.backend.common.observability.MetricsRecorder;
import com.flowpay.backend.common.observability.MetricsSamplingProperties;
import com.flowpay.backend.webhook.application.WebhookMetricsProjection;
import com.flowpay.backend.webhook.application.WebhookMetricsSnapshot;
import io.micrometer.core.instrument.Tags;
import org.springframework.stereotype.Component;

@Component
public class WebhookOperationalMetrics {
    private final WebhookMetricsProjection projection;
    private final DurableMetricsSampler<WebhookMetricsSnapshot> sampler;

    public WebhookOperationalMetrics(WebhookMetricsProjection projection, MetricsRecorder recorder,
                                     MetricsSamplingProperties properties) {
        this.projection = projection;
        this.sampler = new DurableMetricsSampler<>(recorder, properties.fixedDelay(), "WEBHOOK");
        recorder.gauge("flowpay.webhook.backlog", Tags.of("status", "PENDING"), "rows", () -> sampler.value(WebhookMetricsSnapshot::pending));
        recorder.gauge("flowpay.webhook.backlog", Tags.of("status", "RETRYING"), "rows", () -> sampler.value(WebhookMetricsSnapshot::retrying));
        recorder.gauge("flowpay.webhook.backlog", Tags.of("status", "DELIVERING"), "rows", () -> sampler.value(WebhookMetricsSnapshot::delivering));
        recorder.gauge("flowpay.webhook.backlog", Tags.of("status", "DEAD"), "rows", () -> sampler.value(WebhookMetricsSnapshot::dead));
        recorder.gauge("flowpay.webhook.oldest.due.age", Tags.empty(), "seconds", () -> sampler.value(WebhookMetricsSnapshot::oldestDueAgeSeconds));
    }

    public void refresh() { sampler.refresh(projection::snapshot); }
}
