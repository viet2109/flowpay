package com.flowpay.backend.merchant.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateMerchantProfileRequest(
        @NotBlank(message = "Merchant name is required.")
        @Size(max = 200, message = "Merchant name must not exceed 200 characters.")
        String name
) {

    public UpdateMerchantProfileRequest {
        if (name != null) {
            name = name.trim();
        }
    }
}
