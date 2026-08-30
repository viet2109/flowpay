package com.flowpay.backend.identity.application;

import com.flowpay.backend.merchant.domain.MerchantRole;

import java.util.Objects;

public record AuthenticatedIdentity(
        String userPublicId,
        String email,
        String merchantPublicId,
        MerchantRole role
) {

    public AuthenticatedIdentity {
        Objects.requireNonNull(userPublicId, "userPublicId must not be null");
        Objects.requireNonNull(email, "email must not be null");
        Objects.requireNonNull(merchantPublicId, "merchantPublicId must not be null");
        Objects.requireNonNull(role, "role must not be null");
    }
}
