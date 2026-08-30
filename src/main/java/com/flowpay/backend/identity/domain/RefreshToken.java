package com.flowpay.backend.identity.domain;

import java.time.Instant;
import java.util.Objects;

public final class RefreshToken {

    private final Long id;
    private final long userId;
    private final String tokenHash;
    private final Instant expiresAt;
    private Instant revokedAt;
    private final Instant createdAt;
    private Instant lastUsedAt;
    private Long replacedById;

    private RefreshToken(
            Long id,
            long userId,
            String tokenHash,
            Instant expiresAt,
            Instant revokedAt,
            Instant createdAt,
            Instant lastUsedAt,
            Long replacedById
    ) {
        if (id != null && id <= 0) {
            throw new IllegalArgumentException("id must be positive");
        }
        if (userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        this.id = id;
        this.userId = userId;
        this.tokenHash = validateHash(tokenHash);
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        if (!expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("expiresAt must be after createdAt");
        }
        this.revokedAt = validateLifecycleTime(revokedAt, createdAt, "revokedAt");
        this.lastUsedAt = validateLifecycleTime(lastUsedAt, createdAt, "lastUsedAt");
        if (replacedById != null && replacedById <= 0) {
            throw new IllegalArgumentException("replacedById must be positive");
        }
        this.replacedById = replacedById;
    }

    public static RefreshToken create(long userId, String tokenHash, Instant expiresAt, Instant createdAt) {
        return new RefreshToken(null, userId, tokenHash, expiresAt, null, createdAt, null, null);
    }

    public static RefreshToken rehydrate(
            long id,
            long userId,
            String tokenHash,
            Instant expiresAt,
            Instant revokedAt,
            Instant createdAt,
            Instant lastUsedAt,
            Long replacedById
    ) {
        return new RefreshToken(id, userId, tokenHash, expiresAt, revokedAt, createdAt, lastUsedAt, replacedById);
    }

    public boolean isExpired(Instant now) {
        return !expiresAt.isAfter(Objects.requireNonNull(now, "now must not be null"));
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public void rotateTo(long replacementId, Instant usedAt) {
        if (replacementId <= 0) {
            throw new IllegalArgumentException("replacementId must be positive");
        }
        requireUsable(usedAt);
        this.revokedAt = usedAt;
        this.lastUsedAt = usedAt;
        this.replacedById = replacementId;
    }

    public void revoke(Instant revokedAt) {
        if (isRevoked()) {
            throw new IllegalStateException("Refresh token is already revoked");
        }
        Instant time = validateLifecycleTime(revokedAt, createdAt, "revokedAt");
        this.revokedAt = time;
        this.lastUsedAt = time;
    }

    private void requireUsable(Instant now) {
        if (isRevoked()) {
            throw new IllegalStateException("Refresh token is revoked");
        }
        if (isExpired(now)) {
            throw new IllegalStateException("Refresh token is expired");
        }
    }

    private static String validateHash(String tokenHash) {
        Objects.requireNonNull(tokenHash, "tokenHash must not be null");
        if (!tokenHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("tokenHash must be a lowercase SHA-256 hex digest");
        }
        return tokenHash;
    }

    private static Instant validateLifecycleTime(Instant value, Instant createdAt, String field) {
        if (value != null && value.isBefore(createdAt)) {
            throw new IllegalArgumentException(field + " must not be before createdAt");
        }
        return value;
    }

    public Long id() {
        return id;
    }

    public long userId() {
        return userId;
    }

    public String tokenHash() {
        return tokenHash;
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

    public Instant lastUsedAt() {
        return lastUsedAt;
    }

    public Long replacedById() {
        return replacedById;
    }
}
