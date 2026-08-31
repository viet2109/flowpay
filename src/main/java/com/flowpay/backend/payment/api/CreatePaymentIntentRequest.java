package com.flowpay.backend.payment.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.Locale;

public record CreatePaymentIntentRequest(
        @Positive(message = "Amount must be greater than zero.")
        long amount,

        @NotBlank(message = "Currency is required.")
        @Size(min = 3, max = 3, message = "Currency must contain exactly 3 characters.")
        @IsoCurrency
        String currency,

        String orderId,
        String description
) {

    public CreatePaymentIntentRequest {
        if (currency != null) {
            currency = currency.trim().toUpperCase(Locale.ROOT);
        }
    }
}
