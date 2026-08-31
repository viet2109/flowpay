package com.flowpay.backend.idempotency.application;

public record IdempotencyCompletionCommand(
        long executionId,
        String resourceType,
        String resourcePublicId,
        int httpStatus,
        String responsePayload
) {

    public IdempotencyCompletionCommand {
        if (executionId <= 0) {
            throw new IllegalArgumentException("executionId must be positive");
        }
        resourceType = requireText(resourceType, "resourceType");
        resourcePublicId = requireText(resourcePublicId, "resourcePublicId");
        if (httpStatus < 100 || httpStatus > 599) {
            throw new IllegalArgumentException("httpStatus must be between 100 and 599");
        }
        responsePayload = requireText(responsePayload, "responsePayload");
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
