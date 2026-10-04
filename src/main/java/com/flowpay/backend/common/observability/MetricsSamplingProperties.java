package com.flowpay.backend.common.observability;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties("flowpay.metrics.sampling")
public record MetricsSamplingProperties(@DefaultValue("true") boolean enabled,
                                        @DefaultValue("30s") Duration fixedDelay) {
    public MetricsSamplingProperties {
        if (fixedDelay == null || fixedDelay.compareTo(Duration.ofSeconds(10)) < 0
                || fixedDelay.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("Metrics sampling interval must be between 10s and 5m");
        }
    }
}
