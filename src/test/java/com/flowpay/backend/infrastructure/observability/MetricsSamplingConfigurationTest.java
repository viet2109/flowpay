package com.flowpay.backend.infrastructure.observability;

import com.flowpay.backend.common.observability.MetricsSamplingProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MetricsSamplingConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
            .withUserConfiguration(MetricsSamplingConfiguration.class, ExistingScheduling.class);

    @Test
    void shouldUseSeparateNamedSchedulerWithoutReplacingBusinessScheduler() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasBean("taskScheduler").hasBean("operationalMetricsScheduler");
            assertThat(context.getBean("operationalMetricsScheduler", ThreadPoolTaskScheduler.class))
                    .isNotSameAs(context.getBean("taskScheduler"));
            assertThat(context.getBean(MetricsSamplingProperties.class).fixedDelay()).isEqualTo(Duration.ofSeconds(30));
        });
    }

    @Test
    void shouldDisableOnlyMetricsScheduler() {
        runner.withPropertyValues("flowpay.metrics.sampling.enabled=false").run(context -> {
            assertThat(context).hasNotFailed().hasBean("taskScheduler").doesNotHaveBean("operationalMetricsScheduler");
        });
    }

    @ParameterizedTest
    @ValueSource(longs = { -1, 0, 9, 301, Long.MAX_VALUE })
    void shouldRejectUnboundedCadence(long seconds) {
        assertThatThrownBy(() -> new MetricsSamplingProperties(true, Duration.ofSeconds(seconds)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    static class ExistingScheduling {
        @Scheduled(initialDelay = 3_600_000, fixedDelay = 3_600_000) void existingWorker() { }
    }
}
