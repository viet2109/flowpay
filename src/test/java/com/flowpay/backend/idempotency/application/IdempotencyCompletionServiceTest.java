package com.flowpay.backend.idempotency.application;

import com.flowpay.backend.idempotency.domain.IdempotencyKey;
import com.flowpay.backend.idempotency.domain.IdempotencyOperation;
import com.flowpay.backend.idempotency.domain.IdempotencyRecord;
import com.flowpay.backend.idempotency.domain.IdempotencyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IdempotencyCompletionServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-31T10:00:00Z");
    private static final Duration RETENTION = Duration.ofHours(3);

    @Mock
    private IdempotencyRepository repository;

    private IdempotencyCompletionService service;

    @BeforeEach
    void setUp() {
        service = new IdempotencyCompletionService(
                repository,
                Clock.fixed(NOW, ZoneOffset.UTC),
                properties(RETENTION)
        );
    }

    @Test
    void shouldCompleteAndPersistOwnedExecution() {
        IdempotencyRecord record = processingRecord();
        when(repository.findByInternalId(91L)).thenReturn(Optional.of(record));
        IdempotencyCompletionCommand command = command();

        service.complete(command);

        ArgumentCaptor<IdempotencyRecord> saved = ArgumentCaptor.forClass(
                IdempotencyRecord.class
        );
        verify(repository).save(saved.capture());
        assertThat(saved.getValue()).isSameAs(record);
        assertThat(record.isCompleted()).isTrue();
        assertThat(record.resourceType()).isEqualTo("PAYMENT_INTENT");
        assertThat(record.resourcePublicId()).isEqualTo("pi_complete");
        assertThat(record.httpStatus()).isEqualTo(201);
        assertThat(record.responsePayload()).contains("pi_complete");
        assertThat(record.completedAt()).isEqualTo(NOW);
        assertThat(record.expiresAt()).isEqualTo(NOW.plus(RETENTION));
    }

    @Test
    void shouldFailWithoutWritingWhenExecutionCannotBeLoaded() {
        when(repository.findByInternalId(91L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.complete(command()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Idempotency execution could not be loaded");

        verifyNoMoreInteractions(repository);
    }

    private IdempotencyCompletionCommand command() {
        return new IdempotencyCompletionCommand(
                91L,
                "PAYMENT_INTENT",
                "pi_complete",
                201,
                "{\"id\":\"pi_complete\"}"
        );
    }

    private IdempotencyRecord processingRecord() {
        return IdempotencyRecord.rehydrate(
                91L,
                41L,
                IdempotencyOperation.PAYMENT_INTENT_CREATE,
                IdempotencyKey.of("checkout-complete"),
                "a".repeat(64),
                com.flowpay.backend.idempotency.domain.IdempotencyStatus.PROCESSING,
                null,
                null,
                null,
                null,
                NOW.minusSeconds(30),
                null,
                NOW.plusSeconds(3_600)
        );
    }

    private IdempotencyProperties properties(Duration retention) {
        return new IdempotencyProperties(
                retention,
                new IdempotencyProperties.Cleanup(
                        false,
                        100,
                        10,
                        Duration.ZERO,
                        Duration.ofMinutes(15)
                )
        );
    }
}
