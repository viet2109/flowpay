package com.flowpay.backend.merchant.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

public final class ApiKey {

    private static final String PUBLIC_ID_PREFIX = "key_";
    private static final int PUBLIC_ID_MAX_LENGTH = 64;
    private static final int NAME_MAX_LENGTH = 100;
    private static final int KEY_PREFIX_MAX_LENGTH = 64;
    private static final Pattern KEY_PREFIX_PATTERN = Pattern.compile("fp_test_[A-Za-z0-9_-]+");
    private static final Pattern SHA_256_PATTERN = Pattern.compile("[0-9a-f]{64}");

    private final Long id;
    private final String publicId;
    private final long merchantId;
    private final String name;
    private final String keyPrefix;
    private final String keyHash;
    private ApiKeyStatus status;
    private Instant lastUsedAt;
    private final Instant expiresAt;
    private Instant revokedAt;
    private final Instant createdAt;

    private ApiKey(
            Long id,
            String publicId,
            long merchantId,
            String name,
            String keyPrefix,
            String keyHash,
            ApiKeyStatus status,
            Instant lastUsedAt,
            Instant expiresAt,
            Instant revokedAt,
            Instant createdAt
    ) {
        if (id != null && id <= 0) {
            throw new IllegalArgumentException("id must be positive");
        }
        if (merchantId <= 0) {
            throw new IllegalArgumentException("merchantId must be positive");
        }
        this.id = id;
        this.publicId = validatePublicId(publicId);
        this.merchantId = merchantId;
        this.name = validateName(name);
        this.keyPrefix = validateKeyPrefix(keyPrefix);
        this.keyHash = validateKeyHash(keyHash);
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.expiresAt = validateAfterCreated(expiresAt, createdAt, "expiresAt", true);
        this.lastUsedAt = validateAfterCreated(lastUsedAt, createdAt, "lastUsedAt", false);
        this.revokedAt = validateAfterCreated(revokedAt, createdAt, "revokedAt", false);
        validateLifecycleState();
    }

    public static ApiKey create(
            String publicId,
            long merchantId,
            String name,
            String keyPrefix,
            String keyHash,
            Instant expiresAt,
            Instant createdAt
    ) {
        return new ApiKey(
                null,
                publicId,
                merchantId,
                name,
                keyPrefix,
                keyHash,
                ApiKeyStatus.ACTIVE,
                null,
                expiresAt,
                null,
                createdAt
        );
    }

    public static ApiKey rehydrate(
            long id,
            String publicId,
            long merchantId,
            String name,
            String keyPrefix,
            String keyHash,
            ApiKeyStatus status,
            Instant lastUsedAt,
            Instant expiresAt,
            Instant revokedAt,
            Instant createdAt
    ) {
        if (id <= 0) {
            throw new IllegalArgumentException("id must be positive");
        }
        return new ApiKey(
                id,
                publicId,
                merchantId,
                name,
                keyPrefix,
                keyHash,
                status,
                lastUsedAt,
                expiresAt,
                revokedAt,
                createdAt
        );
    }

    public void revoke(Instant revokedAt) {
        if (status == ApiKeyStatus.REVOKED) {
            throw new IllegalStateException("API key is already revoked");
        }
        Instant revocationTime = requireLifecycleTime(revokedAt, "revokedAt");
        if (lastUsedAt != null && revocationTime.isBefore(lastUsedAt)) {
            throw new IllegalArgumentException("revokedAt must not be before lastUsedAt");
        }
        status = ApiKeyStatus.REVOKED;
        this.revokedAt = revocationTime;
    }

    public void recordLastUsed(Instant usedAt) {
        Instant usageTime = requireLifecycleTime(usedAt, "usedAt");
        requireActiveAt(usageTime);
        if (lastUsedAt != null && usageTime.isBefore(lastUsedAt)) {
            throw new IllegalArgumentException("usedAt must not be before lastUsedAt");
        }
        lastUsedAt = usageTime;
    }

    public void requireActiveAt(Instant now) {
        Instant checkedAt = Objects.requireNonNull(now, "now must not be null");
        if (status != ApiKeyStatus.ACTIVE) {
            throw new IllegalStateException("API key is revoked");
        }
        if (isExpiredAt(checkedAt)) {
            throw new IllegalStateException("API key is expired");
        }
    }

    public boolean isExpiredAt(Instant now) {
        Instant checkedAt = Objects.requireNonNull(now, "now must not be null");
        return expiresAt != null && !expiresAt.isAfter(checkedAt);
    }

    private void validateLifecycleState() {
        if (status == ApiKeyStatus.ACTIVE && revokedAt != null) {
            throw new IllegalArgumentException("an active API key must not have revokedAt");
        }
        if (status == ApiKeyStatus.REVOKED && revokedAt == null) {
            throw new IllegalArgumentException("a revoked API key must have revokedAt");
        }
        if (revokedAt != null && lastUsedAt != null && revokedAt.isBefore(lastUsedAt)) {
            throw new IllegalArgumentException("revokedAt must not be before lastUsedAt");
        }
    }

    private Instant requireLifecycleTime(Instant value, String field) {
        Instant time = Objects.requireNonNull(value, field + " must not be null");
        if (time.isBefore(createdAt)) {
            throw new IllegalArgumentException(field + " must not be before createdAt");
        }
        return time;
    }

    private static Instant validateAfterCreated(
            Instant value,
            Instant createdAt,
            String field,
            boolean requireStrictlyAfter
    ) {
        if (value == null) {
            return null;
        }
        boolean invalid = requireStrictlyAfter ? !value.isAfter(createdAt) : value.isBefore(createdAt);
        if (invalid) {
            throw new IllegalArgumentException(field + " must "
                    + (requireStrictlyAfter ? "be after" : "not be before") + " createdAt");
        }
        return value;
    }

    private static String validatePublicId(String publicId) {
        Objects.requireNonNull(publicId, "publicId must not be null");
        if (!publicId.startsWith(PUBLIC_ID_PREFIX) || publicId.length() == PUBLIC_ID_PREFIX.length()) {
            throw new IllegalArgumentException("publicId must start with key_ and contain an identifier");
        }
        if (publicId.length() > PUBLIC_ID_MAX_LENGTH) {
            throw new IllegalArgumentException("publicId must not exceed " + PUBLIC_ID_MAX_LENGTH + " characters");
        }
        return publicId;
    }

    private static String validateName(String name) {
        Objects.requireNonNull(name, "name must not be null");
        String normalized = name.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (normalized.length() > NAME_MAX_LENGTH) {
            throw new IllegalArgumentException("name must not exceed " + NAME_MAX_LENGTH + " characters");
        }
        return normalized;
    }

    private static String validateKeyPrefix(String keyPrefix) {
        Objects.requireNonNull(keyPrefix, "keyPrefix must not be null");
        if (keyPrefix.length() > KEY_PREFIX_MAX_LENGTH || !KEY_PREFIX_PATTERN.matcher(keyPrefix).matches()) {
            throw new IllegalArgumentException("keyPrefix must use the fp_test_ format");
        }
        return keyPrefix;
    }

    private static String validateKeyHash(String keyHash) {
        Objects.requireNonNull(keyHash, "keyHash must not be null");
        if (!SHA_256_PATTERN.matcher(keyHash).matches()) {
            throw new IllegalArgumentException("keyHash must be a lowercase SHA-256 hex digest");
        }
        return keyHash;
    }

    public Long id() {
        return id;
    }

    public String publicId() {
        return publicId;
    }

    public long merchantId() {
        return merchantId;
    }

    public String name() {
        return name;
    }

    public String keyPrefix() {
        return keyPrefix;
    }

    public String keyHash() {
        return keyHash;
    }

    public ApiKeyStatus status() {
        return status;
    }

    public Instant lastUsedAt() {
        return lastUsedAt;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public Instant revokedAt() {
        return revokedAt;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
