package com.flowpay.backend.refund.application;

import com.flowpay.backend.refund.domain.Refund;

import java.util.Optional;

public interface RefundRepository {

    Refund save(Refund refund);

    Optional<Refund> findByPublicIdAndMerchantId(String publicId, long merchantId);

    Optional<Refund> findByPublicIdAndMerchantIdForUpdate(String publicId, long merchantId);

    RefundPage findByPaymentIntentIdAndMerchantId(
            long paymentIntentId,
            long merchantId,
            int page,
            int size
    );
}
