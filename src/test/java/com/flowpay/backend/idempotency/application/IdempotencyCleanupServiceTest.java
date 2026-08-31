package com.flowpay.backend.idempotency.application;

import com.flowpay.backend.idempotency.domain.IdempotencyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IdempotencyCleanupServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-31T12:00:00Z");
    private static final int BATCH_SIZE = 2;

    @Mock
    private IdempotencyRepository repository;

    private IdempotencyCleanupService service;

    @BeforeEach
    void setUp() {
        IdempotencyProperties properties = new IdempotencyProperties(
                IdempotencyProperties.DEFAULT_RETENTION,
                new IdempotencyProperties.Cleanup(
                        false,
                        BATCH_SIZE,
                        3,
                        Duration.ZERO,
                        Duration.ofMinutes(15)
                )
        );
        service = new IdempotencyCleanupService(
                repository,
                Clock.fixed(NOW, ZoneOffset.UTC),
                properties
        );
    }

    @Test
    void shouldDeleteAcrossBoundedBatchesUntilFinalPartialBatch() {
        when(repository.deleteExpiredCompletedBefore(NOW, BATCH_SIZE))
                .thenReturn(BATCH_SIZE, BATCH_SIZE, 1);

        int deleted = service.cleanupExpiredCompleted();

        assertThat(deleted).isEqualTo(5);
        verify(repository, times(3)).deleteExpiredCompletedBefore(NOW, BATCH_SIZE);
    }

    @Test
    void shouldStopAtConfiguredMaximumWhenEveryBatchIsFull() {
        when(repository.deleteExpiredCompletedBefore(NOW, BATCH_SIZE))
                .thenReturn(BATCH_SIZE);

        int deleted = service.cleanupExpiredCompleted();

        assertThat(deleted).isEqualTo(6);
        verify(repository, times(3)).deleteExpiredCompletedBefore(NOW, BATCH_SIZE);
    }

    @Test
    void shouldStopAfterFirstEmptyBatch() {
        when(repository.deleteExpiredCompletedBefore(NOW, BATCH_SIZE)).thenReturn(0);

        assertThat(service.cleanupExpiredCompleted()).isZero();

        verify(repository).deleteExpiredCompletedBefore(NOW, BATCH_SIZE);
    }
}
