package com.flowpay.backend.webhook.application;

import com.flowpay.backend.common.observability.MetricsRecorder;
import io.micrometer.core.instrument.Tags;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class WebhookMetrics {
    private final MetricsRecorder recorder;

    public void claimed() { recorder.incrementAfterCommit("flowpay.webhook.delivery.attempts", Tags.empty()); }
    public void delivered() { recorder.incrementAfterCommit("flowpay.webhook.delivered", Tags.empty()); }
    public void recovered() { recorder.incrementAfterCommit("flowpay.webhook.lease.recoveries", Tags.empty()); }

    public void httpResult(WebhookHttpDeliveryResult result) {
        String outcome = result.httpStatus() == null ? result.failure().name() : "HTTP_" + result.httpStatus() / 100 + "XX";
        recorder.increment("flowpay.webhook.http.outcomes", Tags.of("outcome", outcome));
    }
}
