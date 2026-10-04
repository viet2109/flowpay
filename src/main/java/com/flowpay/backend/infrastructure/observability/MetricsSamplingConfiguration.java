package com.flowpay.backend.infrastructure.observability;

import com.flowpay.backend.common.observability.MetricsSamplingProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MetricsSamplingProperties.class)
public class MetricsSamplingConfiguration {
    // Do not become the default scheduler or suppress Boot's business-worker scheduler.
    @Bean(defaultCandidate = false)
    @ConditionalOnProperty(prefix = "flowpay.metrics.sampling", name = "enabled", havingValue = "true", matchIfMissing = true)
    ThreadPoolTaskScheduler operationalMetricsScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("operational-metrics-");
        scheduler.setDaemon(true);
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setErrorHandler(ignored -> { /* No sensitive diagnostics or business retries. */ });
        return scheduler;
    }
}
