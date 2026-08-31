package com.flowpay.backend.payment.application;

import com.flowpay.backend.common.error.ApiException;
import com.flowpay.backend.common.error.ErrorCode;
import com.flowpay.backend.payment.domain.PaymentIntent;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PaymentConfirmationPreflightService {

    private static final String INVALID_STATE_DETAIL =
            "The payment intent cannot be confirmed from its current state.";

    private final PaymentOwnershipService ownershipService;

    PaymentConfirmationPreflight preflight(ConfirmPaymentCommand command) {
        PaymentIntent payment = ownershipService.requireOwnedPayment(
                command.merchantContext(),
                command.paymentPublicId()
        );
        return new PaymentConfirmationPreflight(
                payment.merchantId(),
                payment.publicId(),
                payment.status()
        );
    }

    static ApiException invalidState() {
        return new ApiException(
                HttpStatus.CONFLICT,
                ErrorCode.PAYMENT_INVALID_STATE,
                INVALID_STATE_DETAIL
        );
    }
}
