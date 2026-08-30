package com.flowpay.backend.idempotency.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

public final class IdempotencyRecord {

    private static final int IDEMPOTENCY_KEY_MAX_LENGTH = 255;
    private static final Pattern REQUEST_HASH_PATTERN = Pattern.compile("[0-9a-f]{64}");

    private final Long internalId;
    private final long merchantId;
    private final String operation;
    private final String idempotencyKey;
    private final String requestHash;
    private IdempotencyStatus status;
    private String resourceType;
    private String resourcePublicId;
    private Integer httpStatus;
    private String responsePayload;
    private final Instant createdAt;
    private Instant completedAt;
    private Instant expiresAt;

    private IdempotencyRecord(
            Long internalId,
            long merchantId,
            String operation,
            String idempotencyKey,
            String requestHash,
            IdempotencyStatus status,
            String resourceType,
            String resourcePublicId,
            Integer httpStatus,
            String responsePayload,
            Instant createdAt,
            Instant completedAt,
            Instant expiresAt
    ) {
        this.internalId = validateInternalId(internalId);
        this.merchantId = validateMerchantId(merchantId);
        this.operation = requireText(operation, "operation");
        this.idempotencyKey = validateIdempotencyKey(idempotencyKey);
        this.requestHash = validateRequestHash(requestHash);
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.resourceType = optionalText(resourceType, "resourceType");
        this.resourcePublicId = optionalText(resourcePublicId, "resourcePublicId");
        this.httpStatus = validateOptionalHttpStatus(httpStatus);
        this.responsePayload = optionalText(responsePayload, "responsePayload");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.completedAt = completedAt;
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        validateLifecycleState();
    }

    public static IdempotencyRecord start(
            long merchantId,
            String operation,
            String idempotencyKey,
            String requestHash,
            Instant createdAt,
            Instant expiresAt
    ) {
        return new IdempotencyRecord(
                null,
                merchantId,
                operation,
                idempotencyKey,
                requestHash,
                IdempotencyStatus.PROCESSING,
                null,
                null,
                null,
                null,
                createdAt,
                null,
                expiresAt
        );
    }

    public static IdempotencyRecord rehydrate(
            long internalId,
            long merchantId,
            String operation,
            String idempotencyKey,
            String requestHash,
            IdempotencyStatus status,
            String resourceType,
            String resourcePublicId,
            Integer httpStatus,
            String responsePayload,
            Instant createdAt,
            Instant completedAt,
            Instant expiresAt
    ) {
        return new IdempotencyRecord(
                internalId,
                merchantId,
                operation,
                idempotencyKey,
                requestHash,
                status,
                resourceType,
                resourcePublicId,
                httpStatus,
                responsePayload,
                createdAt,
                completedAt,
                expiresAt
        );
    }

    public void complete(
            String resourceType,
            String resourcePublicId,
            int httpStatus,
            String responsePayload,
            Instant completedAt,
            Instant expiresAt
    ) {
        if (!isProcessing()) {
            throw new IllegalStateException("Only a PROCESSING idempotency record can be completed");
        }

        String completionResourceType = optionalText(resourceType, "resourceType");
        String completionResourcePublicId = optionalText(resourcePublicId, "resourcePublicId");
        validateResourcePair(completionResourceType, completionResourcePublicId);
        int completionHttpStatus = validateHttpStatus(httpStatus);
        String completionPayload = requireText(responsePayload, "responsePayload");
        Instant completionTime = Objects.requireNonNull(
                completedAt,
                "completedAt must not be null"
        );
        Instant completionExpiry = Objects.requireNonNull(
                expiresAt,
                "expiresAt must not be null"
        );
        validateTimestampOrder(completionTime, completionExpiry);

        this.resourceType = completionResourceType;
        this.resourcePublicId = completionResourcePublicId;
        this.httpStatus = completionHttpStatus;
        this.responsePayload = completionPayload;
        this.completedAt = completionTime;
        this.expiresAt = completionExpiry;
        this.status = IdempotencyStatus.COMPLETED;
    }

    public boolean isCompleted() {
        return status == IdempotencyStatus.COMPLETED;
    }

    public boolean isProcessing() {
        return status == IdempotencyStatus.PROCESSING;
    }

    public boolean isExpired(Instant now) {
        return expiresAt.isBefore(Objects.requireNonNull(now, "now must not be null"));
    }

    private void validateLifecycleState() {
        if (expiresAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("expiresAt must not be before createdAt");
        }
        validateResourcePair(resourceType, resourcePublicId);

        switch (status) {
            case PROCESSING -> {
                if (httpStatus != null || responsePayload != null || completedAt != null) {
                    throw new IllegalArgumentException(
                            "a PROCESSING idempotency record must not have completion data"
                    );
                }
            }
            case COMPLETED -> {
                if (httpStatus == null || responsePayload == null || completedAt == null) {
                    throw new IllegalArgumentException(
                            "a COMPLETED idempotency record must have response and completion data"
                    );
                }
                validateTimestampOrder(completedAt, expiresAt);
            }
        }
    }

    private void validateTimestampOrder(Instant completionTime, Instant completionExpiry) {
        if (completionTime.isBefore(createdAt)) {
            throw new IllegalArgumentException("completedAt must not be before createdAt");
        }
        if (completionExpiry.isBefore(completionTime)) {
            throw new IllegalArgumentException("expiresAt must not be before completedAt");
        }
    }

    private static void validateResourcePair(String resourceType, String resourcePublicId) {
        if ((resourceType == null) != (resourcePublicId == null)) {
            throw new IllegalArgumentException(
                    "resourceType and resourcePublicId must either both be present or both be absent"
            );
        }
    }

    private static Long validateInternalId(Long internalId) {
        if (internalId != null && internalId <= 0) {
            throw new IllegalArgumentException("internalId must be positive");
        }
        return internalId;
    }

    private static long validateMerchantId(long merchantId) {
        if (merchantId <= 0) {
            throw new IllegalArgumentException("merchantId must be positive");
        }
        return merchantId;
    }

    private static String validateIdempotencyKey(String idempotencyKey) {
        String value = requireText(idempotencyKey, "idempotencyKey");
        if (value.length() > IDEMPOTENCY_KEY_MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "idempotencyKey must not exceed " + IDEMPOTENCY_KEY_MAX_LENGTH + " characters"
            );
        }
        return value;
    }

    private static String validateRequestHash(String requestHash) {
        Objects.requireNonNull(requestHash, "requestHash must not be null");
        if (!REQUEST_HASH_PATTERN.matcher(requestHash).matches()) {
            throw new IllegalArgumentException(
                    "requestHash must contain exactly 64 lowercase hexadecimal characters"
            );
        }
        return requestHash;
    }

    private static Integer validateOptionalHttpStatus(Integer httpStatus) {
        return httpStatus == null ? null : validateHttpStatus(httpStatus);
    }

    private static int validateHttpStatus(int httpStatus) {
        if (httpStatus < 100 || httpStatus > 599) {
            throw new IllegalArgumentException("httpStatus must be between 100 and 599");
        }
        return httpStatus;
    }

    private static String requireText(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }

    private static String optionalText(String value, String fieldName) {
        if (value == null) {
            return null;
        }
        return requireText(value, fieldName);
    }

    public Long internalId() {
        return internalId;
    }

    public long merchantId() {
        return merchantId;
    }

    public String operation() {
        return operation;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }

    public String requestHash() {
        return requestHash;
    }

    public IdempotencyStatus status() {
        return status;
    }

    public String resourceType() {
        return resourceType;
    }

    public String resourcePublicId() {
        return resourcePublicId;
    }

    public Integer httpStatus() {
        return httpStatus;
    }

    public String responsePayload() {
        return responsePayload;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant completedAt() {
        return completedAt;
    }

    public Instant expiresAt() {
        return expiresAt;
    }
}
