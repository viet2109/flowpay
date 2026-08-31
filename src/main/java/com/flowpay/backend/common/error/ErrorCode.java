package com.flowpay.backend.common.error;

public enum ErrorCode {
    VALIDATION_ERROR("Validation failed", "validation-error"),
    MALFORMED_REQUEST("Malformed request", "malformed-request"),
    AUTHENTICATION_REQUIRED("Authentication required", "authentication-required"),
    ACCESS_DENIED("Access denied", "access-denied"),
    USER_EMAIL_ALREADY_EXISTS("User email already exists", "user-email-already-exists"),
    INVALID_CREDENTIALS("Invalid credentials", "invalid-credentials"),
    USER_LOCKED("User locked", "user-locked"),
    USER_DISABLED("User disabled", "user-disabled"),
    REFRESH_TOKEN_INVALID("Refresh token invalid", "refresh-token-invalid"),
    REFRESH_TOKEN_EXPIRED("Refresh token expired", "refresh-token-expired"),
    REFRESH_TOKEN_REVOKED("Refresh token revoked", "refresh-token-revoked"),
    MERCHANT_NOT_FOUND("Merchant not found", "merchant-not-found"),
    MERCHANT_SUSPENDED("Merchant suspended", "merchant-suspended"),
    API_KEY_NOT_FOUND("API key not found", "api-key-not-found"),
    API_KEY_REVOKED("API key revoked", "api-key-revoked"),
    INVALID_API_KEY("Invalid API key", "invalid-api-key"),
    IDEMPOTENCY_KEY_REQUIRED("Idempotency key required", "idempotency-key-required"),
    IDEMPOTENCY_KEY_REUSED("Idempotency key reused", "idempotency-key-reused"),
    IDEMPOTENCY_REQUEST_IN_PROGRESS(
            "Idempotency request in progress",
            "idempotency-request-in-progress"
    ),
    PAYMENT_NOT_FOUND("Payment not found", "payment-not-found"),
    PAYMENT_INVALID_STATE("Payment invalid state", "payment-invalid-state"),
    REFUND_INVALID_PAYMENT_STATE(
            "Refund invalid payment state",
            "refund-invalid-payment-state"
    ),
    REFUND_AMOUNT_EXCEEDS_AVAILABLE(
            "Refund amount exceeds available",
            "refund-amount-exceeds-available"
    ),
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
