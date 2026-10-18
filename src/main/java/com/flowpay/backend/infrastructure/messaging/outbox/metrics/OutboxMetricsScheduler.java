package com.flowpay.backend.infrastructure.messaging.outbox.metrics;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "flowpay.metrics.sampling", name = "enabled", havingValue = "true", matchIfMissing = true)
class OutboxMetricsScheduler {
    private final OutboxOperationalMetrics metrics;

    @Scheduled(scheduler = "operationalMetricsScheduler", fixedDelayString = "${flowpay.metrics.sampling.fixed-delay:30s}",
            initialDelayString = "${flowpay.metrics.sampling.fixed-delay:30s}")
    void refresh() { metrics.refresh(); }
}
