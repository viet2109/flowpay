package com.flowpay.backend.common.observability;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class MetricsRecorderTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MetricsRecorder recorder = new MetricsRecorder(registry);

    @AfterEach
    void cleanUp() {
        TransactionSynchronizationManager.clear();
        registry.close();
    }

    @Test
    void shouldNotInventCommittedEventsOutsideAnActualTransaction() {
        recorder.incrementAfterCommit("test.committed", Tags.empty());
        TransactionSynchronizationManager.initSynchronization();
        recorder.incrementAfterCommit("test.committed", Tags.empty());
        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
        assertThat(registry.getMeters()).isEmpty();
    }

    @Test
    void shouldRecordOnlyWhenTheExistingTransactionCommits() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        recorder.incrementAfterCommit("test.committed", Tags.of("outcome", "SUCCESS"));
        assertThat(registry.getMeters()).isEmpty();
        TransactionSynchronizationUtils.invokeAfterCommit(TransactionSynchronizationManager.getSynchronizations());
        assertThat(registry.get("test.committed").counter().count()).isEqualTo(1);
    }

    @Test
    void shouldIgnoreFailedRegistryRegistrationAndAfterCommitWithoutLoggingTheCause() {
        registry.config().meterFilter(new MeterFilter() {
            @Override
            public Meter.Id map(Meter.Id id) {
                throw new IllegalStateException("synthetic-sensitive-diagnostic");
            }
        });
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        assertThatCode(() -> {
            recorder.increment("test.attempt", Tags.empty());
            recorder.recordProviderDuration("test.provider.duration", "UNKNOWN", 1);
            recorder.incrementAfterCommit("test.committed", Tags.empty());
            TransactionSynchronizationUtils.invokeAfterCommit(TransactionSynchronizationManager.getSynchronizations());
        }).doesNotThrowAnyException();
        assertThat(registry.getMeters()).isEmpty();
    }
}
