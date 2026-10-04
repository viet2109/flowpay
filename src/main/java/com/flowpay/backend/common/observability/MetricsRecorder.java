package com.flowpay.backend.common.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/** Best-effort technical recording only: no repository reads, business decisions or new transactions. */
@Component
@RequiredArgsConstructor
public class MetricsRecorder {

    private final MeterRegistry registry;

    public void increment(String name, Tags tags) {
        try {
            Counter.builder(name).tags(tags).register(registry).increment();
        } catch (RuntimeException ignored) {
            // Telemetry must not change an operation's outcome or leak diagnostic contents.
        }
    }

    public void incrementAfterCommit(String name, Tags tags) {
        try {
            if (!TransactionSynchronizationManager.isActualTransactionActive()
                    || !TransactionSynchronizationManager.isSynchronizationActive()) {
                return;
            }
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    increment(name, tags);
                }
            });
        } catch (RuntimeException ignored) {
            // Missing synchronization/registration is not a reason to fail business persistence.
        }
    }

    public void recordProviderDuration(String name, String outcome, long elapsedNanos) {
        try {
            Timer.builder(name).tag("outcome", outcome)
                    .serviceLevelObjectives(Duration.ofMillis(10), Duration.ofMillis(50),
                            Duration.ofMillis(100), Duration.ofMillis(500), Duration.ofSeconds(1),
                            Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofSeconds(30))
                    .register(registry).record(Math.max(0, elapsedNanos), TimeUnit.NANOSECONDS);
        } catch (RuntimeException ignored) {
            // Export/registry failures are isolated; never log provider or request material.
        }
    }
}
