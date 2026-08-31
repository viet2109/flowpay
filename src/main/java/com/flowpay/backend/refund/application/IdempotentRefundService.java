package com.flowpay.backend.refund.application;

import com.flowpay.backend.idempotency.application.IdempotencyCompletionCommand;
import com.flowpay.backend.idempotency.application.IdempotencyCompletionService;
import com.flowpay.backend.idempotency.application.IdempotencyKeyReusedException;
import com.flowpay.backend.idempotency.application.IdempotencyRequestInProgressException;
import com.flowpay.backend.idempotency.application.IdempotencyStoredResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class IdempotentRefundService {

    static final String REFUND_RESOURCE = "REFUND";

    private final PrepareRefundService preparationService;
    private final RefundProviderPort refundProvider;
    private final FinalizeRefundService finalizationService;
    private final RefundResponseSnapshotMapper snapshotMapper;
    private final RefundResponseSnapshotCodec snapshotCodec;
    private final IdempotencyCompletionService completionService;

    public IdempotentRefundResult create(IdempotentRefundCommand command) {
        PrepareRefundResult preparation = preparationService.prepare(
                command.toPrepareCommand()
        );
        return switch (preparation.decision()) {
            case NEW -> executeNew(preparation.prepared());
            case REPLAY -> replay(preparation.replayResponse());
            case IN_PROGRESS -> throw new IdempotencyRequestInProgressException();
            case KEY_REUSED -> throw new IdempotencyKeyReusedException();
        };
    }

    private IdempotentRefundResult executeNew(PreparedRefund prepared) {
        RefundProviderResult providerResult = refundProvider.refund(
                prepared.toProviderRequest()
        );
        FinalizedRefund finalized = finalizationService.finalizeRefund(
                new FinalizeRefundCommand(
                        prepared.merchantInternalId(),
                        prepared.refundPublicId(),
                        prepared.paymentPublicId(),
                        providerResult
                )
        );
        RefundResponseSnapshot snapshot = snapshotMapper.toSnapshot(finalized);
        int httpStatus = snapshot.httpStatus();
        completionService.complete(new IdempotencyCompletionCommand(
                prepared.idempotencyExecutionId(),
                REFUND_RESOURCE,
                snapshot.id(),
                httpStatus,
                snapshotCodec.encode(snapshot)
        ));
        return new IdempotentRefundResult(snapshot, httpStatus, false);
    }

    private IdempotentRefundResult replay(IdempotencyStoredResponse stored) {
        if (!REFUND_RESOURCE.equals(stored.resourceType())) {
            throw inconsistentReplay();
        }
        RefundResponseSnapshot snapshot = snapshotCodec.decode(stored.responsePayload());
        if (!stored.resourcePublicId().equals(snapshot.id())
                || stored.httpStatus() != snapshot.httpStatus()) {
            throw inconsistentReplay();
        }
        return new IdempotentRefundResult(snapshot, stored.httpStatus(), true);
    }

    private static IllegalStateException inconsistentReplay() {
        return new IllegalStateException("Stored Refund response is inconsistent");
    }
}
