package com.flowpay.backend.common.error;

public enum ErrorCode {
    VALIDATION_ERROR("Validation failed", "validation-error"),
    MALFORMED_REQUEST("Malformed request", "malformed-request"),
    USER_EMAIL_ALREADY_EXISTS("User email already exists", "user-email-already-exists"),
    INVALID_CREDENTIALS("Invalid credentials", "invalid-credentials"),
    USER_LOCKED("User locked", "user-locked"),
    USER_DISABLED("User disabled", "user-disabled"),
    REFRESH_TOKEN_INVALID("Refresh token invalid", "refresh-token-invalid"),
    REFRESH_TOKEN_EXPIRED("Refresh token expired", "refresh-token-expired"),
    REFRESH_TOKEN_REVOKED("Refresh token revoked", "refresh-token-revoked"),
    MERCHANT_NOT_FOUND("Merchant not found", "merchant-not-found"),
    RESOURCE_NOT_FOUND("Resource not found", "resource-not-found"),
    INTERNAL_ERROR("Internal server error", "internal-error");

    private final String title;
    private final String slug;

    ErrorCode(String title, String slug) {
        this.title = title;
        this.slug = slug;
    }

    public String title() {
        return title;
    }

    public String slug() {
        return slug;
    }
}
