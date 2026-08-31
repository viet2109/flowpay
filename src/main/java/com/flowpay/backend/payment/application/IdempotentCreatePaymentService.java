package com.flowpay.backend.payment.application;

import com.flowpay.backend.idempotency.application.CreatePaymentFingerprint;
import com.flowpay.backend.idempotency.application.IdempotencyAcquisitionCommand;
import com.flowpay.backend.idempotency.application.IdempotencyAcquisitionResult;
import com.flowpay.backend.idempotency.application.IdempotencyAcquisitionService;
import com.flowpay.backend.idempotency.application.IdempotencyCompletionCommand;
import com.flowpay.backend.idempotency.application.IdempotencyCompletionService;
import com.flowpay.backend.idempotency.application.IdempotencyKeyReusedException;
import com.flowpay.backend.idempotency.application.IdempotencyRequestInProgressException;
import com.flowpay.backend.idempotency.application.IdempotencyStoredResponse;
import com.flowpay.backend.idempotency.application.RequestFingerprintService;
import com.flowpay.backend.idempotency.domain.IdempotencyOperation;
import com.flowpay.backend.merchant.application.ActiveMerchantSnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class IdempotentCreatePaymentService {

    static final int CREATED_HTTP_STATUS = 201;
    static final String PAYMENT_INTENT_RESOURCE = "PAYMENT_INTENT";

    private final PaymentMerchantResolver merchantResolver;
    private final RequestFingerprintService fingerprintService;
    private final IdempotencyAcquisitionService acquisitionService;
    private final CreatePaymentIntentService createPaymentIntentService;
    private final CreatePaymentResponseSnapshotMapper snapshotMapper;
    private final CreatePaymentResponseSnapshotCodec snapshotCodec;
    private final IdempotencyCompletionService completionService;

    @Transactional
    public IdempotentCreatePaymentResult create(IdempotentCreatePaymentCommand command) {
        CreatePaymentFingerprint fingerprint = CreatePaymentFingerprint.version1(
                command.amountMinor(),
                command.currency(),
                command.orderId(),
                command.description()
        );
        String requestHash = fingerprintService.fingerprint(fingerprint);
        ActiveMerchantSnapshot merchant = merchantResolver.resolve(command.merchantContext());
        IdempotencyAcquisitionResult acquisition = acquisitionService.acquire(
                new IdempotencyAcquisitionCommand(
                        merchant.internalId(),
                        IdempotencyOperation.PAYMENT_INTENT_CREATE,
                        command.idempotencyKey(),
                        requestHash
                )
        );

        return switch (acquisition.decision()) {
            case NEW -> createNew(command, merchant, acquisition.executionId());
            case REPLAY -> replay(acquisition.replayResponse());
            case IN_PROGRESS -> throw new IdempotencyRequestInProgressException();
            case KEY_REUSED -> throw new IdempotencyKeyReusedException();
        };
    }

    private IdempotentCreatePaymentResult createNew(
            IdempotentCreatePaymentCommand command,
            ActiveMerchantSnapshot merchant,
            long executionId
    ) {
        CreatePaymentIntentResult created = createPaymentIntentService.createForResolvedMerchant(
                command.toCreateCommand(),
                merchant
        );
        CreatePaymentResponseSnapshot snapshot = snapshotMapper.toSnapshot(created);
        completionService.complete(new IdempotencyCompletionCommand(
                executionId,
                PAYMENT_INTENT_RESOURCE,
                snapshot.id(),
                CREATED_HTTP_STATUS,
                snapshotCodec.encode(snapshot)
        ));
        return new IdempotentCreatePaymentResult(snapshot, CREATED_HTTP_STATUS, false);
    }

    private IdempotentCreatePaymentResult replay(IdempotencyStoredResponse stored) {
        if (!PAYMENT_INTENT_RESOURCE.equals(stored.resourceType())
                || stored.httpStatus() != CREATED_HTTP_STATUS) {
            throw new IllegalStateException("Stored create payment response is inconsistent");
        }
        CreatePaymentResponseSnapshot snapshot = snapshotCodec.decode(stored.responsePayload());
        if (!stored.resourcePublicId().equals(snapshot.id())) {
            throw new IllegalStateException("Stored create payment resource is inconsistent");
        }
        return new IdempotentCreatePaymentResult(snapshot, stored.httpStatus(), true);
    }
}
