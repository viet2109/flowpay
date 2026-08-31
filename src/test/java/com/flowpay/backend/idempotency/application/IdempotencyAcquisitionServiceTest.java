package com.flowpay.backend.idempotency.application;

import com.flowpay.backend.idempotency.domain.IdempotencyKey;
import com.flowpay.backend.idempotency.domain.IdempotencyOperation;
import com.flowpay.backend.idempotency.domain.IdempotencyRecord;
import com.flowpay.backend.idempotency.domain.IdempotencyRepository;
import com.flowpay.backend.idempotency.domain.IdempotencyStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IdempotencyAcquisitionServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-31T08:00:00Z");
    private static final String REQUEST_HASH = "a".repeat(64);
    private static final String OTHER_HASH = "b".repeat(64);
    private static final IdempotencyKey KEY = IdempotencyKey.of("checkout-1001");

    @Mock
    private IdempotencyRepository repository;

    private IdempotencyAcquisitionService service;

    @BeforeEach
    void setUp() {
        service = new IdempotencyAcquisitionService(
                repository,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void shouldReturnNewWhenAtomicInsertSucceeds() {
        when(repository.tryInsert(any())).thenAnswer(invocation -> Optional.of(
                persisted(invocation.getArgument(0), 71L)
        ));

        IdempotencyAcquisitionResult result = service.acquire(command(REQUEST_HASH));

        assertThat(result.decision()).isEqualTo(IdempotencyAcquisitionDecision.NEW);
        assertThat(result.executionId()).isEqualTo(71L);
        assertThat(result.replayResponse()).isNull();
        ArgumentCaptor<IdempotencyRecord> candidate = ArgumentCaptor.forClass(
                IdempotencyRecord.class
        );
        verify(repository).tryInsert(candidate.capture());
        assertThat(candidate.getValue().isProcessing()).isTrue();
        assertThat(candidate.getValue().createdAt()).isEqualTo(NOW);
        assertThat(candidate.getValue().expiresAt())
                .isEqualTo(NOW.plus(IdempotencyRetention.DEFAULT));
        verify(repository, never()).findByScope(anyLong(), any(), any());
    }

    @Test
    void shouldReturnReplayForSameHashCompletedRecord() {
        IdempotencyRecord existing = completedRecord(REQUEST_HASH);
        when(repository.tryInsert(any())).thenReturn(Optional.empty());
        when(repository.findByScope(
                41L,
                IdempotencyOperation.PAYMENT_INTENT_CREATE,
                KEY
        )).thenReturn(Optional.of(existing));

        IdempotencyAcquisitionResult result = service.acquire(command(REQUEST_HASH));

        assertThat(result.decision()).isEqualTo(IdempotencyAcquisitionDecision.REPLAY);
        assertThat(result.executionId()).isNull();
        assertThat(result.replayResponse()).isEqualTo(new IdempotencyStoredResponse(
                "PAYMENT_INTENT",
                "pi_1001",
                201,
                "{\"data\":{\"id\":\"pi_1001\"}}"
        ));
    }

    @Test
    void shouldReturnInProgressForSameHashProcessingRecord() {
        IdempotencyRecord existing = processingRecord(REQUEST_HASH);
        when(repository.tryInsert(any())).thenReturn(Optional.empty());
        when(repository.findByScope(
                41L,
                IdempotencyOperation.PAYMENT_INTENT_CREATE,
                KEY
        )).thenReturn(Optional.of(existing));

        IdempotencyAcquisitionResult result = service.acquire(command(REQUEST_HASH));

        assertThat(result.decision()).isEqualTo(IdempotencyAcquisitionDecision.IN_PROGRESS);
        assertThat(result.executionId()).isNull();
        assertThat(result.replayResponse()).isNull();
    }

    @Test
    void shouldReturnKeyReusedBeforeConsideringExistingStatus() {
        IdempotencyRecord existing = completedRecord(REQUEST_HASH);
        when(repository.tryInsert(any())).thenReturn(Optional.empty());
        when(repository.findByScope(
                41L,
                IdempotencyOperation.PAYMENT_INTENT_CREATE,
                KEY
        )).thenReturn(Optional.of(existing));

        IdempotencyAcquisitionResult result = service.acquire(command(OTHER_HASH));

        assertThat(result.decision()).isEqualTo(IdempotencyAcquisitionDecision.KEY_REUSED);
        assertThat(result.executionId()).isNull();
        assertThat(result.replayResponse()).isNull();
    }

    @Test
    void shouldFailWithoutLeakingScopeWhenConflictCannotBeLoaded() {
        when(repository.tryInsert(any())).thenReturn(Optional.empty());
        when(repository.findByScope(
                41L,
                IdempotencyOperation.PAYMENT_INTENT_CREATE,
                KEY
        )).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.acquire(command(REQUEST_HASH)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Conflicting idempotency record could not be loaded")
                .hasMessageNotContaining(KEY.value());
    }

    @Test
    void shouldValidateRequestHashBeforeCallingPersistence() {
        assertThatThrownBy(() -> service.acquire(command("not-a-sha256")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("requestHash must contain exactly 64 lowercase hexadecimal characters");

        verify(repository, never()).tryInsert(any());
    }

    @Test
    void resultShouldProtectDecisionStateConsistency() {
        assertThatThrownBy(() -> new IdempotencyAcquisitionResult(
                IdempotencyAcquisitionDecision.REPLAY,
                1L,
                null
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("REPLAY decision requires only a stored response");
        assertThatThrownBy(() -> new IdempotencyAcquisitionResult(
                IdempotencyAcquisitionDecision.NEW,
                null,
                null
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("NEW decision requires only an executionId");
    }

    private IdempotencyAcquisitionCommand command(String requestHash) {
        return new IdempotencyAcquisitionCommand(
                41L,
                IdempotencyOperation.PAYMENT_INTENT_CREATE,
                KEY,
                requestHash
        );
    }

    private IdempotencyRecord processingRecord(String requestHash) {
        return IdempotencyRecord.rehydrate(
                70L,
                41L,
                IdempotencyOperation.PAYMENT_INTENT_CREATE,
                KEY,
                requestHash,
                IdempotencyStatus.PROCESSING,
                null,
                null,
                null,
                null,
                NOW.minusSeconds(30),
                null,
                NOW.plusSeconds(3_600)
        );
    }

    private IdempotencyRecord completedRecord(String requestHash) {
        IdempotencyRecord record = processingRecord(requestHash);
        record.complete(
                "PAYMENT_INTENT",
                "pi_1001",
                201,
                "{\"data\":{\"id\":\"pi_1001\"}}",
                NOW.minusSeconds(10),
                NOW.plusSeconds(86_390)
        );
        return record;
    }

    private IdempotencyRecord persisted(IdempotencyRecord record, long internalId) {
        return IdempotencyRecord.rehydrate(
                internalId,
                record.merchantId(),
                record.operation(),
                record.idempotencyKey(),
                record.requestHash(),
                record.status(),
                record.resourceType(),
                record.resourcePublicId(),
                record.httpStatus(),
                record.responsePayload(),
                record.createdAt(),
                record.completedAt(),
                record.expiresAt()
        );
    }
}
