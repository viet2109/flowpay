package com.flowpay.backend.payment.application;

import com.flowpay.backend.payment.domain.PaymentIntent;

import java.util.List;
import java.util.Optional;

public interface PaymentIntentRepository {

    PaymentIntent save(PaymentIntent paymentIntent);

    Optional<PaymentIntent> findByPublicId(String publicId);

    Optional<PaymentIntent> findByPublicIdAndMerchantId(String publicId, long merchantId);

    Optional<PaymentIntent> findByInternalIdAndMerchantId(long internalId, long merchantId);

    Optional<PaymentIntent> findByPublicIdAndMerchantIdForUpdate(
            String publicId,
            long merchantId
    );

    PaymentIntentPage search(PaymentIntentSearchCriteria criteria);

    List<PaymentIntent> searchByMerchant(long merchantId);
}
