package com.flowpay.backend.refund.application;

import com.flowpay.backend.idempotency.application.IdempotencyAcquisitionCommand;
import com.flowpay.backend.idempotency.application.IdempotencyAcquisitionResult;
import com.flowpay.backend.idempotency.application.IdempotencyAcquisitionService;
import com.flowpay.backend.idempotency.application.RequestFingerprintService;
import com.flowpay.backend.idempotency.domain.IdempotencyOperation;
import com.flowpay.backend.merchant.application.ActiveMerchantSnapshot;
import com.flowpay.backend.merchant.application.MerchantAccessApi;
import com.flowpay.backend.payment.application.PaymentRefundApi;
import com.flowpay.backend.payment.application.PaymentRefundReservation;
import com.flowpay.backend.refund.domain.Refund;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

@Service
@RequiredArgsConstructor
public class PrepareRefundService {

    private final MerchantAccessApi merchantAccessApi;
    private final RequestFingerprintService fingerprintService;
    private final IdempotencyAcquisitionService acquisitionService;
    private final PaymentRefundApi paymentRefundApi;
    private final RefundPublicIdGenerator refundPublicIdGenerator;
    private final RefundRepository refundRepository;
    private final Clock clock;

    @Transactional
    public PrepareRefundResult prepare(PrepareRefundCommand command) {
        ActiveMerchantSnapshot merchant = merchantAccessApi.requireActiveMerchant(
                command.merchantContext().merchantPublicId()
        );
        String requestHash = fingerprintService.fingerprint(
                CreateRefundFingerprint.version1(
                        command.paymentPublicId(),
                        command.amountMinor(),
                        command.reason()
                )
        );
        IdempotencyAcquisitionResult acquisition = acquisitionService.acquire(
                new IdempotencyAcquisitionCommand(
                        merchant.internalId(),
                        IdempotencyOperation.REFUND_CREATE,
                        command.idempotencyKey(),
                        requestHash
                )
        );

        return switch (acquisition.decision()) {
            case NEW -> prepareNew(command, merchant, acquisition.executionId());
            case REPLAY -> PrepareRefundResult.replay(acquisition.replayResponse());
            case IN_PROGRESS -> PrepareRefundResult.inProgress();
            case KEY_REUSED -> PrepareRefundResult.keyReused();
        };
    }

    private PrepareRefundResult prepareNew(
            PrepareRefundCommand command,
            ActiveMerchantSnapshot merchant,
            long executionId
    ) {
        PaymentRefundReservation reservation = paymentRefundApi.reserveRefund(
                merchant.internalId(),
                command.paymentPublicId(),
                command.amountMinor()
        );
        String refundPublicId = refundPublicIdGenerator.nextId();
        Instant preparedAt = clock.instant();
        Refund refund = Refund.create(
                refundPublicId,
                merchant.internalId(),
                reservation.paymentInternalId(),
                reservation.amount(),
                command.reason(),
                reservation.provider(),
                preparedAt
        );
        refund.startProcessing(preparedAt);
        Refund saved = refundRepository.save(refund);

        return PrepareRefundResult.prepared(new PreparedRefund(
                executionId,
                merchant.internalId(),
                saved.publicId(),
                reservation.paymentPublicId(),
                saved.amount(),
                saved.reason(),
                saved.provider(),
                reservation.providerTransactionId()
        ));
    }
}
