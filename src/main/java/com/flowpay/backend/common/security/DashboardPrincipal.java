package com.flowpay.backend.common.security;

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

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
