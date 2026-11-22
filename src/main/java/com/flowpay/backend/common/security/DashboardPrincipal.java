package com.flowpay.backend.common.security;

import com.flowpay.backend.common.tenant.MerchantContext;

public record DashboardPrincipal(
        String userPublicId,
        String merchantPublicId,
        String role
) {

    public DashboardPrincipal {
        userPublicId = requireText(userPublicId, "userPublicId");
        merchantPublicId = requireText(merchantPublicId, "merchantPublicId");
        role = requireText(role, "role");
    }

    /** The merchant this session acts for (the token's {@code merchant} claim). */
    public MerchantContext merchantContext() {
        return new MerchantContext(merchantPublicId);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
