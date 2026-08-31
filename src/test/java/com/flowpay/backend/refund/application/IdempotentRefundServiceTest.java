package com.flowpay.backend.refund.application;

import com.flowpay.backend.common.money.Money;
import com.flowpay.backend.common.security.MerchantApiPrincipal;
import com.flowpay.backend.idempotency.application.IdempotencyCompletionCommand;
import com.flowpay.backend.idempotency.application.IdempotencyCompletionService;
import com.flowpay.backend.idempotency.application.IdempotencyKeyReusedException;
import com.flowpay.backend.idempotency.application.IdempotencyRequestInProgressException;
import com.flowpay.backend.idempotency.application.IdempotencyStoredResponse;
import com.flowpay.backend.idempotency.domain.IdempotencyKey;
import com.flowpay.backend.refund.domain.RefundProviderOutcome;
import com.flowpay.backend.refund.domain.RefundReason;
import com.flowpay.backend.refund.domain.RefundStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IdempotentRefundServiceTest {

    private static final Instant CREATED_AT = Instant.parse("2026-09-01T00:00:00Z");
    private static final IdempotentRefundCommand COMMAND = new IdempotentRefundCommand(
            new MerchantApiPrincipal("mrc_refund_orchestration", "key_refund_orchestration"),
            IdempotencyKey.of("refund-order-1001"),
            "pi_refund_orchestration",
            20_000L,
            RefundReason.of("Customer request")
    );
    private static final PreparedRefund PREPARED = new PreparedRefund(
            71L,
            41L,
            "re_refund_orchestration",
            "pi_refund_orchestration",
            Money.of(20_000L, "VND"),
            RefundReason.of("Customer request"),
            "SIMULATOR",
            "provider_charge_1001"
    );
    private static final RefundProviderResult PROVIDER_SUCCESS = new RefundProviderResult(
            "SIMULATOR",
            RefundProviderOutcome.SUCCESS,
            "provider_refund_1001",
            null,
            null
    );
    private static final FinalizedRefund FINALIZED = new FinalizedRefund(
            "re_refund_orchestration",
            "pi_refund_orchestration",
            Money.of(20_000L, "VND"),
            RefundStatus.SUCCEEDED,
            RefundReason.of("Customer request"),
            "SIMULATOR",
            "provider_refund_1001",
            null,
            null,
            CREATED_AT,
            CREATED_AT.plusSeconds(1),
            CREATED_AT.plusSeconds(1)
    );
    private static final RefundResponseSnapshot SNAPSHOT = new RefundResponseSnapshot(
            "re_refund_orchestration",
            "pi_refund_orchestration",
            20_000L,
            "VND",
            RefundStatus.SUCCEEDED,
            "Customer request",
            "SIMULATOR",
            "provider_refund_1001",
            null,
            null,
            CREATED_AT,
            CREATED_AT.plusSeconds(1),
            CREATED_AT.plusSeconds(1)
    );
    private static final String SNAPSHOT_JSON = "{\"id\":\"re_refund_orchestration\"}";

    @Mock
    private PrepareRefundService preparationService;

    @Mock
    private RefundProviderPort refundProvider;

    @Mock
    private FinalizeRefundService finalizationService;

    @Mock
    private RefundResponseSnapshotMapper snapshotMapper;

    @Mock
    private RefundResponseSnapshotCodec snapshotCodec;

    @Mock
    private IdempotencyCompletionService completionService;

    private IdempotentRefundService service;

    @BeforeEach
    void setUp() {
        service = new IdempotentRefundService(
                preparationService,
                refundProvider,
                finalizationService,
                snapshotMapper,
                snapshotCodec,
                completionService
        );
    }

    @Test
    void newRequestShouldExecuteBoundariesInRequiredOrderAndCompleteSnapshot() {
        PrepareRefundResult preparation = PrepareRefundResult.prepared(PREPARED);
        FinalizeRefundCommand finalizeCommand = new FinalizeRefundCommand(
                PREPARED.merchantInternalId(),
                PREPARED.refundPublicId(),
                PREPARED.paymentPublicId(),
                PROVIDER_SUCCESS
        );
        when(preparationService.prepare(COMMAND.toPrepareCommand())).thenReturn(preparation);
        when(refundProvider.refund(PREPARED.toProviderRequest())).thenReturn(PROVIDER_SUCCESS);
        when(finalizationService.finalizeRefund(finalizeCommand)).thenReturn(FINALIZED);
        when(snapshotMapper.toSnapshot(FINALIZED)).thenReturn(SNAPSHOT);
        when(snapshotCodec.encode(SNAPSHOT)).thenReturn(SNAPSHOT_JSON);

        IdempotentRefundResult result = service.create(COMMAND);

        assertThat(result).isEqualTo(new IdempotentRefundResult(SNAPSHOT, 201, false));
        InOrder order = inOrder(
                preparationService,
                refundProvider,
                finalizationService,
                snapshotMapper,
                snapshotCodec,
                completionService
        );
        order.verify(preparationService).prepare(COMMAND.toPrepareCommand());
        order.verify(refundProvider).refund(PREPARED.toProviderRequest());
        order.verify(finalizationService).finalizeRefund(finalizeCommand);
        order.verify(snapshotMapper).toSnapshot(FINALIZED);
        order.verify(snapshotCodec).encode(SNAPSHOT);
        order.verify(completionService).complete(new IdempotencyCompletionCommand(
                71L,
                "REFUND",
                "re_refund_orchestration",
                201,
                SNAPSHOT_JSON
        ));
    }

    @Test
    void replayShouldReturnStoredSnapshotWithoutExecutingRefund() {
        IdempotencyStoredResponse stored = new IdempotencyStoredResponse(
                "REFUND",
                SNAPSHOT.id(),
                201,
                SNAPSHOT_JSON
        );
        when(preparationService.prepare(COMMAND.toPrepareCommand())).thenReturn(
                PrepareRefundResult.replay(stored)
        );
        when(snapshotCodec.decode(SNAPSHOT_JSON)).thenReturn(SNAPSHOT);

        IdempotentRefundResult result = service.create(COMMAND);

        assertThat(result).isEqualTo(new IdempotentRefundResult(SNAPSHOT, 201, true));
        verifyNoInteractions(
                refundProvider,
                finalizationService,
                snapshotMapper,
                completionService
        );
        verify(snapshotCodec, never()).encode(SNAPSHOT);
    }

    @Test
    void duplicateConflictDecisionsShouldNeverReachProvider() {
        when(preparationService.prepare(COMMAND.toPrepareCommand()))
                .thenReturn(PrepareRefundResult.inProgress())
                .thenReturn(PrepareRefundResult.keyReused());

        assertThatThrownBy(() -> service.create(COMMAND))
                .isInstanceOf(IdempotencyRequestInProgressException.class);
        assertThatThrownBy(() -> service.create(COMMAND))
                .isInstanceOf(IdempotencyKeyReusedException.class);

        verifyNoInteractions(
                refundProvider,
                finalizationService,
                snapshotMapper,
                snapshotCodec,
                completionService
        );
    }

    @Test
    void unexpectedProviderFailureShouldPreservePreparedOwnership() {
        RuntimeException uncertain = new RuntimeException("provider connection closed");
        when(preparationService.prepare(COMMAND.toPrepareCommand())).thenReturn(
                PrepareRefundResult.prepared(PREPARED)
        );
        when(refundProvider.refund(PREPARED.toProviderRequest())).thenThrow(uncertain);

        assertThatThrownBy(() -> service.create(COMMAND)).isSameAs(uncertain);

        verifyNoInteractions(
                finalizationService,
                snapshotMapper,
                snapshotCodec,
                completionService
        );
    }

    @Test
    void completionFailureShouldNotAllowProviderReexecution() {
        RuntimeException completionFailure = new RuntimeException("completion unavailable");
        when(preparationService.prepare(COMMAND.toPrepareCommand()))
                .thenReturn(PrepareRefundResult.prepared(PREPARED))
                .thenReturn(PrepareRefundResult.inProgress());
        when(refundProvider.refund(PREPARED.toProviderRequest())).thenReturn(PROVIDER_SUCCESS);
        when(finalizationService.finalizeRefund(org.mockito.ArgumentMatchers.any()))
                .thenReturn(FINALIZED);
        when(snapshotMapper.toSnapshot(FINALIZED)).thenReturn(SNAPSHOT);
        when(snapshotCodec.encode(SNAPSHOT)).thenReturn(SNAPSHOT_JSON);
        org.mockito.Mockito.doThrow(completionFailure)
                .when(completionService)
                .complete(org.mockito.ArgumentMatchers.any());

        assertThatThrownBy(() -> service.create(COMMAND)).isSameAs(completionFailure);
        assertThatThrownBy(() -> service.create(COMMAND))
                .isInstanceOf(IdempotencyRequestInProgressException.class);

        verify(refundProvider).refund(PREPARED.toProviderRequest());
    }

    @Test
    void replayShouldRejectInconsistentResourceIdentityAndStatus() {
        IdempotencyStoredResponse wrongResource = new IdempotencyStoredResponse(
                "PAYMENT_INTENT",
                SNAPSHOT.id(),
                201,
                SNAPSHOT_JSON
        );
        IdempotencyStoredResponse wrongId = new IdempotencyStoredResponse(
                "REFUND",
                "re_other",
                201,
                SNAPSHOT_JSON
        );
        IdempotencyStoredResponse wrongStatus = new IdempotencyStoredResponse(
                "REFUND",
                SNAPSHOT.id(),
                202,
                SNAPSHOT_JSON
        );
        when(preparationService.prepare(COMMAND.toPrepareCommand()))
                .thenReturn(PrepareRefundResult.replay(wrongResource))
                .thenReturn(PrepareRefundResult.replay(wrongId))
                .thenReturn(PrepareRefundResult.replay(wrongStatus));
        when(snapshotCodec.decode(SNAPSHOT_JSON)).thenReturn(SNAPSHOT);

        assertThatThrownBy(() -> service.create(COMMAND))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Stored Refund response is inconsistent");
        assertThatThrownBy(() -> service.create(COMMAND))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Stored Refund response is inconsistent");
        assertThatThrownBy(() -> service.create(COMMAND))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Stored Refund response is inconsistent");

        verifyNoInteractions(
                refundProvider,
                finalizationService,
                snapshotMapper,
                completionService
        );
    }

    @Test
    void malformedReplayShouldFailWithoutExecutingOrQueryingRefundState() {
        IdempotencyStoredResponse malformed = new IdempotencyStoredResponse(
                "REFUND",
                SNAPSHOT.id(),
                201,
                "{malformed"
        );
        when(preparationService.prepare(COMMAND.toPrepareCommand())).thenReturn(
                PrepareRefundResult.replay(malformed)
        );
        when(snapshotCodec.decode("{malformed")).thenThrow(
                new IllegalStateException("Stored Refund response snapshot is invalid")
        );

        assertThatThrownBy(() -> service.create(COMMAND))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Stored Refund response snapshot is invalid");

        verifyNoInteractions(
                refundProvider,
                finalizationService,
                snapshotMapper,
                completionService
        );
    }

    @Test
    void orchestratorMustNotOpenAnOuterDatabaseTransaction() throws Exception {
        assertThat(IdempotentRefundService.class.getAnnotation(Transactional.class)).isNull();
        assertThat(IdempotentRefundService.class
                .getDeclaredMethod("create", IdempotentRefundCommand.class)
                .getAnnotation(Transactional.class)).isNull();
        assertThat(Arrays.stream(IdempotentRefundService.class.getDeclaredFields())
                .map(field -> field.getType().getName()))
                .noneMatch(type -> type.contains("TransactionTemplate")
                        || type.contains("TransactionManager"));
    }
}
