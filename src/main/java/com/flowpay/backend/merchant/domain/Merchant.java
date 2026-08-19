package com.flowpay.backend.merchant.domain;

import java.time.Instant;
import java.util.Objects;

public final class Merchant {

    private static final String PUBLIC_ID_PREFIX = "mrc_";
    private static final int PUBLIC_ID_MAX_LENGTH = 64;
    private static final int NAME_MAX_LENGTH = 200;

    private final Long id;
    private final String publicId;
    private final String name;
    private final MerchantStatus status;
    private final long version;
    private final Instant createdAt;
    private final Instant updatedAt;

    private Merchant(
            Long id,
            String publicId,
            String name,
            MerchantStatus status,
            long version,
            Instant createdAt,
            Instant updatedAt
    ) {
        this.id = id;
        this.publicId = validatePublicId(publicId);
        this.name = validateName(name);
        this.status = Objects.requireNonNull(status, "status must not be null");
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
        this.version = version;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not be before createdAt");
        }
    }

    public static Merchant create(String publicId, String name, Instant now) {
        Instant createdAt = Objects.requireNonNull(now, "now must not be null");
        return new Merchant(
                null,
                publicId,
                name,
                MerchantStatus.ACTIVE,
                0,
                createdAt,
                createdAt
        );
    }

    public static Merchant rehydrate(
            long id,
            String publicId,
            String name,
            MerchantStatus status,
            long version,
            Instant createdAt,
            Instant updatedAt
    ) {
        if (id <= 0) {
            throw new IllegalArgumentException("id must be positive");
        }
        return new Merchant(id, publicId, name, status, version, createdAt, updatedAt);
    }

    private static String validatePublicId(String publicId) {
        Objects.requireNonNull(publicId, "publicId must not be null");
        if (!publicId.startsWith(PUBLIC_ID_PREFIX) || publicId.length() == PUBLIC_ID_PREFIX.length()) {
            throw new IllegalArgumentException("publicId must start with mrc_ and contain an identifier");
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

    public Long id() {
        return id;
    }

    public String publicId() {
        return publicId;
    }

    public String name() {
        return name;
    }

    public MerchantStatus status() {
        return status;
    }

    public long version() {
        return version;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
