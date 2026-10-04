package com.flowpay.backend.common.observability;

import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@ExtendWith(OutputCaptureExtension.class)
class DurableMetricsSamplerTest {
    @Test
    void shouldNeverSampleInsideACallerTransaction() {
        var registry = new SimpleMeterRegistry();
        try {
            var sampler = new DurableMetricsSampler<Integer>(new MetricsRecorder(registry), Duration.ofSeconds(30), "OUTBOX");
            TransactionSynchronizationManager.setActualTransactionActive(true);
            try { sampler.refresh(() -> { throw new AssertionError("query inside financial transaction"); }); }
            finally { TransactionSynchronizationManager.clear(); }
            assertThat(sampler.value(Integer::doubleValue)).isNaN();
            assertThat(registry.find("flowpay.metrics.sampling").counter()).isNull();
            sampler.refresh(() -> 4);
            assertThat(sampler.value(Integer::doubleValue)).isEqualTo(4);
        } finally { registry.close(); }
    }

    @Test
    void shouldRetainStaleSnapshotAndThrottleFailuresWithoutScrapeQueries(CapturedOutput output) {
        var registry = new SimpleMeterRegistry();
        try {
            var ticker = new AtomicLong();
            var calls = new AtomicInteger();
            var sampler = new DurableMetricsSampler<Integer>(new MetricsRecorder(registry), Duration.ofSeconds(30), "OUTBOX", ticker::get);
            assertThat(sampler.value(Integer::doubleValue)).isNaN();
            assertThat(sampler.ageSeconds()).isNaN();
            sampler.refresh(() -> { calls.incrementAndGet(); throw new IllegalStateException("sensitive SQL URL payload"); });
            assertThat(sampler.value(Integer::doubleValue)).isNaN();
            sampler.refresh(() -> { calls.incrementAndGet(); return 99; });
            assertThat(calls).hasValue(1);
            ticker.addAndGet(Duration.ofSeconds(30).toNanos());
            sampler.refresh(() -> { calls.incrementAndGet(); return 7; });
            ticker.addAndGet(Duration.ofSeconds(30).toNanos());
            sampler.refresh(() -> { calls.incrementAndGet(); throw new IllegalStateException("sensitive SQL URL payload"); });
            for (int scrape = 0; scrape < 100; scrape++) {
                assertThat(sampler.value(Integer::doubleValue)).isEqualTo(7);
                assertThat(registry.get("flowpay.metrics.sample.age").gauge().value()).isEqualTo(30);
                sampler.refresh(() -> { calls.incrementAndGet(); return 99; });
            }
            assertThat(calls).hasValue(3);
            assertThat(registry.get("flowpay.metrics.sampling").tag("outcome", "FAILURE").counter().count()).isEqualTo(2);
            assertThat(registry.get("flowpay.metrics.sampling.duration").timer().count()).isEqualTo(3);
            assertThat(output.getAll()).doesNotContain("sensitive SQL URL payload");
        } finally { registry.close(); }
    }

    @Test
    void shouldPreventOverlappingQueriesEvenWhenIntervalHasElapsed() throws Exception {
        var registry = new SimpleMeterRegistry();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var ticker = new AtomicLong();
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var sampler = new DurableMetricsSampler<Integer>(new MetricsRecorder(registry), Duration.ofSeconds(30), "WEBHOOK", ticker::get);
            var query = executor.submit(() -> sampler.refresh(() -> {
                entered.countDown();
                try { assertThat(release.await(5, TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException exception) { throw new IllegalStateException("fixture interrupted"); }
                return 5;
            }));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                ticker.addAndGet(Duration.ofSeconds(60).toNanos());
                sampler.refresh(() -> { throw new AssertionError("overlapping query"); });
                assertThat(sampler.value(Integer::doubleValue)).isNaN();
            } finally { release.countDown(); }
            query.get(5, TimeUnit.SECONDS);
            assertThat(sampler.value(Integer::doubleValue)).isEqualTo(5);
        } finally { registry.close(); }
    }

    @Test
    void shouldKeepSuccessfulSamplingWhenRegistryCannotRegister() {
        var registry = new SimpleMeterRegistry();
        try {
            registry.config().meterFilter(new MeterFilter() {
                @Override public Meter.Id map(Meter.Id id) { throw new IllegalStateException("sensitive registry cause"); }
            });
            var sampler = new DurableMetricsSampler<Integer>(new MetricsRecorder(registry), Duration.ofSeconds(30), "OUTBOX");
            assertThatCode(() -> sampler.refresh(() -> 8)).doesNotThrowAnyException();
            assertThat(sampler.value(Integer::doubleValue)).isEqualTo(8);
        } finally { registry.close(); }
    }
}
