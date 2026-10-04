package com.flowpay.backend.refund.application;

import com.flowpay.backend.common.error.ApiException;
import com.flowpay.backend.common.error.ErrorCode;
import com.flowpay.backend.payment.application.PaymentRefundApi;
import com.flowpay.backend.refund.application.event.RefundIntegrationEventPublisher;
import com.flowpay.backend.refund.application.event.RefundSucceededEventV1;
import com.flowpay.backend.refund.application.event.RefundFailedEventV1;
import com.flowpay.backend.refund.domain.Refund;
import com.flowpay.backend.refund.domain.RefundFailure;
import com.flowpay.backend.refund.domain.RefundProviderOutcome;
import com.flowpay.backend.refund.domain.RefundStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Service
@RequiredArgsConstructor
public class FinalizeRefundService {

    private static final String NOT_FOUND_DETAIL = "The refund was not found.";

    private final RefundRepository refundRepository;
    private final PaymentRefundApi paymentRefundApi;
    private final RefundIntegrationEventPublisher eventPublisher;
    private final Clock clock;

    @Transactional
    public FinalizedRefund finalizeRefund(FinalizeRefundCommand command) {
        Refund refund = refundRepository.findByPublicIdAndMerchantIdForUpdate(
                command.refundPublicId(),
                command.merchantInternalId()
        ).orElseThrow(FinalizeRefundService::notFound);
        requireFinalizable(refund, command.providerResult());

        Instant finalizedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        applyOutcome(refund, command, finalizedAt);
        Refund saved = refundRepository.save(refund);
        publishTerminalRefund(saved, command.paymentPublicId(), finalizedAt);
        return toResult(saved, command.paymentPublicId());
    }

    private void publishTerminalRefund(
            Refund refund,
            String paymentPublicId,
            Instant finalizedAt
    ) {
        if (refund.status() == RefundStatus.FAILED) {
            eventPublisher.publish(new RefundFailedEventV1(
                    refund.merchantId(), refund.publicId(), paymentPublicId,
                    refund.amount().amountMinor(), refund.amount().currency().getCurrencyCode(),
                    refund.failureCode(), refund.failureMessage(), finalizedAt
            ));
        } else if (refund.status() == RefundStatus.SUCCEEDED) {
            eventPublisher.publish(new RefundSucceededEventV1(
                    refund.merchantId(), refund.publicId(), paymentPublicId,
                    refund.amount().amountMinor(), refund.amount().currency().getCurrencyCode(), finalizedAt
            ));
        }
    }

    private void applyOutcome(
            Refund refund,
            FinalizeRefundCommand command,
            Instant finalizedAt
    ) {
        RefundProviderResult providerResult = command.providerResult();
        RefundProviderOutcome outcome = providerResult.outcome();
        switch (outcome) {
            case SUCCESS -> {
                paymentRefundApi.completeRefund(
                        command.merchantInternalId(),
                        command.paymentPublicId(),
                        refund.paymentIntentId(),
                        refund.amount()
                );
                refund.markSucceeded(providerResult.providerRefundId(), finalizedAt);
            }
            case DECLINED, TECHNICAL_FAILURE -> {
                paymentRefundApi.releaseRefund(
                        command.merchantInternalId(),
                        command.paymentPublicId(),
                        refund.paymentIntentId(),
                        refund.amount()
                );
                refund.markFailed(
                        providerResult.providerRefundId(),
                        failure(providerResult),
                        finalizedAt
                );
            }
            case UNKNOWN -> refund.recordUnknownProviderResult(
                    providerResult.providerRefundId(),
                    failure(providerResult),
                    finalizedAt
            );
        }
    }

    private static void requireFinalizable(
            Refund refund,
            RefundProviderResult providerResult
    ) {
        if (refund.status() != RefundStatus.PROCESSING) {
            throw new IllegalStateException(
                    "Refund cannot be finalized from " + refund.status()
            );
        }
        if (!refund.provider().equals(providerResult.provider())) {
            throw new IllegalStateException(
                    "Refund provider result does not match the prepared provider"
            );
        }
    }

    private static RefundFailure failure(RefundProviderResult providerResult) {
        return RefundFailure.of(
                providerResult.failureCode(),
                providerResult.failureMessage()
        );
    }

    private static FinalizedRefund toResult(Refund refund, String paymentPublicId) {
        return new FinalizedRefund(
                refund.publicId(),
                paymentPublicId,
                refund.amount(),
                refund.status(),
                refund.reason(),
                refund.provider(),
                refund.providerRefundId(),
                refund.failureCode(),
                refund.failureMessage(),
                refund.createdAt(),
                refund.updatedAt(),
                refund.completedAt()
        );
    }

    private static ApiException notFound() {
        return new ApiException(
                HttpStatus.NOT_FOUND,
                ErrorCode.REFUND_NOT_FOUND,
                NOT_FOUND_DETAIL
        );
    }
}
