package com.flowpay.backend.payment.application;

import com.flowpay.backend.idempotency.application.ConfirmPaymentFingerprint;
import com.flowpay.backend.idempotency.application.IdempotencyAcquisitionCommand;
import com.flowpay.backend.idempotency.application.IdempotencyAcquisitionResult;
import com.flowpay.backend.idempotency.application.IdempotencyAcquisitionService;
import com.flowpay.backend.idempotency.application.IdempotencyCompletionCommand;
import com.flowpay.backend.idempotency.application.IdempotencyCompletionService;
import com.flowpay.backend.idempotency.application.IdempotencyKeyReusedException;
import com.flowpay.backend.idempotency.application.IdempotencyRequestInProgressException;
import com.flowpay.backend.idempotency.application.IdempotencyReservationReleaseCommand;
import com.flowpay.backend.idempotency.application.IdempotencyReservationReleaseService;
import com.flowpay.backend.idempotency.application.IdempotencyStoredResponse;
import com.flowpay.backend.idempotency.application.RequestFingerprintService;
import com.flowpay.backend.idempotency.domain.IdempotencyOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class IdempotentConfirmPaymentService {

    static final String PAYMENT_INTENT_RESOURCE = "PAYMENT_INTENT";

    private final RequestFingerprintService fingerprintService;
    private final PaymentConfirmationPreflightService preflightService;
    private final IdempotencyAcquisitionService acquisitionService;
    private final IdempotencyReservationReleaseService releaseService;
    private final ConfirmPaymentService confirmationService;
    private final ConfirmPaymentResponseSnapshotMapper snapshotMapper;
    private final ConfirmPaymentResponseSnapshotCodec snapshotCodec;
    private final IdempotencyCompletionService completionService;

    public IdempotentConfirmPaymentResult confirm(IdempotentConfirmPaymentCommand command) {
        ConfirmPaymentCommand confirmCommand = command.toConfirmCommand();
        String requestHash = fingerprintService.fingerprint(
                ConfirmPaymentFingerprint.version1(command.paymentPublicId())
        );
        PaymentConfirmationPreflight preflight = preflightService.preflight(confirmCommand);
        IdempotencyAcquisitionCommand acquisitionCommand =
                IdempotencyAcquisitionCommand.forResource(
                        preflight.merchantId(),
                        IdempotencyOperation.PAYMENT_INTENT_CONFIRM,
                        command.idempotencyKey(),
                        requestHash,
                        PAYMENT_INTENT_RESOURCE,
                        preflight.paymentPublicId()
                );
        IdempotencyAcquisitionResult acquisition = acquireForState(
                preflight,
                acquisitionCommand
        );

        return switch (acquisition.decision()) {
            case NEW -> executeNew(
                    command,
                    confirmCommand,
                    acquisitionCommand,
                    acquisition.executionId()
            );
            case REPLAY -> replay(acquisition.replayResponse());
            case IN_PROGRESS -> throw new IdempotencyRequestInProgressException();
            case KEY_REUSED -> throw new IdempotencyKeyReusedException();
        };
    }

    private IdempotencyAcquisitionResult acquireForState(
            PaymentConfirmationPreflight preflight,
            IdempotencyAcquisitionCommand acquisitionCommand
    ) {
        if (preflight.isConfirmable()) {
            return acquisitionService.acquire(acquisitionCommand);
        }
        return acquisitionService.findExisting(acquisitionCommand)
                .orElseThrow(PaymentConfirmationPreflightService::invalidState);
    }

    private IdempotentConfirmPaymentResult executeNew(
            IdempotentConfirmPaymentCommand command,
            ConfirmPaymentCommand confirmCommand,
            IdempotencyAcquisitionCommand acquisitionCommand,
            long executionId
    ) {
        PreparedPaymentConfirmation prepared;
        try {
            prepared = confirmationService.prepare(confirmCommand);
        } catch (RuntimeException preparationFailure) {
            safelyRelease(
                    releaseCommand(command, acquisitionCommand, executionId),
                    preparationFailure
            );
            throw preparationFailure;
        }

        FinalizedPaymentConfirmation confirmation = confirmationService.executePrepared(prepared);
        ConfirmPaymentResponseSnapshot snapshot = snapshotMapper.toSnapshot(confirmation);
        int httpStatus = snapshot.httpStatus();
        completionService.complete(new IdempotencyCompletionCommand(
                executionId,
                PAYMENT_INTENT_RESOURCE,
                snapshot.paymentId(),
                httpStatus,
                snapshotCodec.encode(snapshot)
        ));
        return new IdempotentConfirmPaymentResult(
                snapshot,
                httpStatus,
                false
        );
    }

    private IdempotentConfirmPaymentResult replay(IdempotencyStoredResponse stored) {
        if (!PAYMENT_INTENT_RESOURCE.equals(stored.resourceType())) {
            throw new IllegalStateException("Stored confirm payment response is inconsistent");
        }
        ConfirmPaymentResponseSnapshot snapshot = snapshotCodec.decode(
                stored.responsePayload()
        );
        if (!stored.resourcePublicId().equals(snapshot.paymentId())
                || stored.httpStatus() != snapshot.httpStatus()) {
            throw new IllegalStateException("Stored confirm payment response is inconsistent");
        }
        return new IdempotentConfirmPaymentResult(
                snapshot,
                stored.httpStatus(),
                true
        );
    }

    private void safelyRelease(
            IdempotencyReservationReleaseCommand command,
            RuntimeException preparationFailure
    ) {
        try {
            releaseService.release(command);
        } catch (RuntimeException releaseFailure) {
            preparationFailure.addSuppressed(releaseFailure);
        }
    }

    private static IdempotencyReservationReleaseCommand releaseCommand(
            IdempotentConfirmPaymentCommand command,
            IdempotencyAcquisitionCommand acquisitionCommand,
            long executionId
    ) {
        return new IdempotencyReservationReleaseCommand(
                executionId,
                acquisitionCommand.merchantId(),
                acquisitionCommand.operation(),
                command.idempotencyKey(),
                acquisitionCommand.requestHash(),
                acquisitionCommand.resourceType(),
                acquisitionCommand.resourcePublicId()
        );
    }
}
