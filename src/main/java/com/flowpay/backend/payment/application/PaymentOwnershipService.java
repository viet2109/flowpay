package com.flowpay.backend.payment.application;

import com.flowpay.backend.common.error.ApiException;
import com.flowpay.backend.common.error.ErrorCode;
import com.flowpay.backend.common.security.MerchantApiPrincipal;
import com.flowpay.backend.merchant.application.ActiveMerchantSnapshot;
import com.flowpay.backend.payment.domain.PaymentIntent;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PaymentOwnershipService {

    private final PaymentMerchantResolver merchantResolver;
    private final PaymentIntentRepository paymentIntentRepository;

    public PaymentIntent requireOwnedPayment(
            MerchantApiPrincipal principal,
            String paymentPublicId
    ) {
        String publicId = requirePaymentPublicId(paymentPublicId);
        ActiveMerchantSnapshot merchant = merchantResolver.resolve(principal);
        return paymentIntentRepository.findByPublicIdAndMerchantId(
                publicId,
                merchant.internalId()
        ).orElseThrow(PaymentOwnershipService::paymentNotFound);
    }

    private static String requirePaymentPublicId(String paymentPublicId) {
        if (paymentPublicId == null || paymentPublicId.isBlank()) {
            throw new IllegalArgumentException("paymentPublicId must not be blank");
        }
        return paymentPublicId;
    }

    private static ApiException paymentNotFound() {
        return new ApiException(
                HttpStatus.NOT_FOUND,
                ErrorCode.PAYMENT_NOT_FOUND,
                "The payment intent was not found."
        );
    }
}
