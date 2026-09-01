package com.flowpay.backend.payment.application;

import com.flowpay.backend.common.money.Money;

/**
 * Public Payment module contract for merchant-owned refund capacity.
 */
public interface PaymentRefundApi {

    OwnedPaymentSnapshot requireOwnedPayment(long merchantInternalId, String paymentPublicId);

    OwnedPaymentSnapshot requireOwnedPaymentByInternalId(
            long merchantInternalId,
            long paymentInternalId
    );

    PaymentRefundReservation reserveRefund(
            long merchantInternalId,
            String paymentPublicId,
            long amountMinor
    );

    void completeRefund(
            long merchantInternalId,
            String paymentPublicId,
            Money amount
    );

    void releaseRefund(
            long merchantInternalId,
            String paymentPublicId,
            Money amount
    );
}
