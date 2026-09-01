package com.flowpay.backend.payment.application;

import com.flowpay.backend.common.error.ApiException;
import com.flowpay.backend.common.error.ErrorCode;
import com.flowpay.backend.common.money.Money;
import com.flowpay.backend.payment.domain.PaymentIntent;
import com.flowpay.backend.payment.domain.PaymentStatus;
import com.flowpay.backend.payment.domain.PaymentTransaction;
import com.flowpay.backend.payment.domain.PaymentTransactionStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PaymentRefundService implements PaymentRefundApi {

    private static final String PAYMENT_NOT_FOUND_DETAIL = "The payment intent was not found.";
    private static final String INVALID_STATE_DETAIL =
            "The payment intent cannot be refunded from its current state.";
    private static final String EXCEEDS_AVAILABLE_DETAIL =
            "The refund amount exceeds the available payment amount.";

    private final PaymentIntentRepository paymentIntentRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public OwnedPaymentSnapshot requireOwnedPayment(
            long merchantInternalId,
            String paymentPublicId
    ) {
        PaymentIntent payment = findOwnedPayment(merchantInternalId, paymentPublicId, false);
        return new OwnedPaymentSnapshot(payment.internalId(), payment.publicId());
    }

    @Override
    @Transactional(readOnly = true)
    public OwnedPaymentSnapshot requireOwnedPaymentByInternalId(
            long merchantInternalId,
            long paymentInternalId
    ) {
        if (merchantInternalId <= 0) {
            throw new IllegalArgumentException("merchantInternalId must be positive");
        }
        if (paymentInternalId <= 0) {
            throw new IllegalArgumentException("paymentInternalId must be positive");
        }
        PaymentIntent payment = paymentIntentRepository
                .findByInternalIdAndMerchantId(paymentInternalId, merchantInternalId)
                .orElseThrow(PaymentRefundService::paymentNotFound);
        return new OwnedPaymentSnapshot(payment.internalId(), payment.publicId());
    }

    @Override
    @Transactional
    public PaymentRefundReservation reserveRefund(
            long merchantInternalId,
            String paymentPublicId,
            long amountMinor
    ) {
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("amountMinor must be positive");
        }
        PaymentIntent payment = findOwnedPayment(merchantInternalId, paymentPublicId, true);
        requireRefundableState(payment);
        Money amount = new Money(amountMinor, payment.amount().currency());
        if (amount.compareTo(payment.refundableAmount()) > 0) {
            throw amountExceedsAvailable();
        }
        PaymentTransaction successfulCharge = requireSuccessfulCharge(payment.internalId());

        payment.reserveRefund(amount, clock.instant());
        paymentIntentRepository.save(payment);
        return new PaymentRefundReservation(
                payment.internalId(),
                payment.publicId(),
                payment.merchantId(),
                amount,
                successfulCharge.provider(),
                successfulCharge.providerTransactionId()
        );
    }

    @Override
    @Transactional
    public void completeRefund(
            long merchantInternalId,
            String paymentPublicId,
            Money amount
    ) {
        PaymentIntent payment = findOwnedPayment(merchantInternalId, paymentPublicId, true);
        payment.completeRefund(amount, clock.instant());
        paymentIntentRepository.save(payment);
    }

    @Override
    @Transactional
    public void releaseRefund(
            long merchantInternalId,
            String paymentPublicId,
            Money amount
    ) {
        PaymentIntent payment = findOwnedPayment(merchantInternalId, paymentPublicId, true);
        payment.releaseRefund(amount, clock.instant());
        paymentIntentRepository.save(payment);
    }

    private PaymentIntent findOwnedPayment(
            long merchantInternalId,
            String paymentPublicId,
            boolean forUpdate
    ) {
        if (merchantInternalId <= 0) {
            throw new IllegalArgumentException("merchantInternalId must be positive");
        }
        if (paymentPublicId == null || paymentPublicId.isBlank()) {
            throw new IllegalArgumentException("paymentPublicId must not be blank");
        }
        return (forUpdate
                ? paymentIntentRepository.findByPublicIdAndMerchantIdForUpdate(
                        paymentPublicId,
                        merchantInternalId
                )
                : paymentIntentRepository.findByPublicIdAndMerchantId(
                        paymentPublicId,
                        merchantInternalId
                )).orElseThrow(PaymentRefundService::paymentNotFound);
    }

    private static void requireRefundableState(PaymentIntent payment) {
        if (payment.status() != PaymentStatus.SUCCEEDED
                && payment.status() != PaymentStatus.PARTIALLY_REFUNDED) {
            throw invalidPaymentState();
        }
    }

    private PaymentTransaction requireSuccessfulCharge(long paymentInternalId) {
        List<PaymentTransaction> succeeded = paymentTransactionRepository
                .findByPaymentIntentIdAndStatus(
                        paymentInternalId,
                        PaymentTransactionStatus.SUCCEEDED
                );
        if (succeeded.size() != 1 || succeeded.getFirst().providerTransactionId() == null) {
            throw new IllegalStateException(
                    "Payment must have exactly one successful provider transaction reference"
            );
        }
        return succeeded.getFirst();
    }

    private static ApiException paymentNotFound() {
        return new ApiException(
                HttpStatus.NOT_FOUND,
                ErrorCode.PAYMENT_NOT_FOUND,
                PAYMENT_NOT_FOUND_DETAIL
        );
    }

    private static ApiException invalidPaymentState() {
        return new ApiException(
                HttpStatus.CONFLICT,
                ErrorCode.REFUND_INVALID_PAYMENT_STATE,
                INVALID_STATE_DETAIL
        );
    }

    private static ApiException amountExceedsAvailable() {
        return new ApiException(
                HttpStatus.CONFLICT,
                ErrorCode.REFUND_AMOUNT_EXCEEDS_AVAILABLE,
                EXCEEDS_AVAILABLE_DETAIL
        );
    }
}
