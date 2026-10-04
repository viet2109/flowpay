package com.flowpay.backend.common.observability;

import io.micrometer.core.instrument.Tags;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.function.ToDoubleFunction;

/** Query-free cache/cadence mechanics. Only the owning caller supplies its read-only projection. */
public final class DurableMetricsSampler<T> {
    private final MetricsRecorder recorder;
    private final Tags tags;
    private final long intervalNanos;
    private final LongSupplier ticker;
    private volatile Sample<T> sample;
    private boolean running;
    private boolean attempted;
    private long lastAttempt;

    public DurableMetricsSampler(MetricsRecorder recorder, Duration interval, String component) {
        this(recorder, interval, component, System::nanoTime);
    }

    // Explicit monotonic-clock test seam; never use wall-clock jumps for cadence or freshness.
    DurableMetricsSampler(MetricsRecorder recorder, Duration interval, String component, LongSupplier ticker) {
        this.recorder = Objects.requireNonNull(recorder);
        this.intervalNanos = Objects.requireNonNull(interval).toNanos();
        if (intervalNanos <= 0) throw new IllegalArgumentException("Sampling interval must be positive");
        if (!"OUTBOX".equals(component) && !"WEBHOOK".equals(component)) {
            throw new IllegalArgumentException("Unsupported metrics component");
        }
        this.tags = Tags.of("component", component);
        this.ticker = Objects.requireNonNull(ticker);
        recorder.gauge("flowpay.metrics.sample.age", tags, "seconds", this::ageSeconds);
    }

    public void refresh(Supplier<T> projection) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) return;
        long started = ticker.getAsLong();
        synchronized (this) {
            if (running || (attempted && started - lastAttempt < intervalNanos)) return;
            running = true;
            attempted = true;
            lastAttempt = started;
        }
        try {
            T value = Objects.requireNonNull(projection.get());
            sample = new Sample<>(value, ticker.getAsLong());
            recorder.increment("flowpay.metrics.sampling", tags.and("outcome", "SUCCESS"));
        } catch (RuntimeException ignored) {
            // Keep stale data visibly stale; never fabricate zeros or log a SQL/provider cause.
            recorder.increment("flowpay.metrics.sampling", tags.and("outcome", "FAILURE"));
        } finally {
            recorder.recordDuration("flowpay.metrics.sampling.duration", tags, ticker.getAsLong() - started);
            synchronized (this) {
                running = false;
            }
        }
    }

    public double value(ToDoubleFunction<T> extractor) {
        Sample<T> current = sample;
        return current == null ? Double.NaN : extractor.applyAsDouble(current.value());
    }

    public double ageSeconds() {
        Sample<T> current = sample;
        return current == null ? Double.NaN : Math.max(0, ticker.getAsLong() - current.sampledAt()) / 1_000_000_000.0;
    }

    private record Sample<T>(T value, long sampledAt) { }
}
