package com.flowpay.backend.identity.domain;

import java.time.Instant;
import java.util.Objects;

public final class User {

    private static final String PUBLIC_ID_PREFIX = "usr_";
    private static final int PUBLIC_ID_MAX_LENGTH = 64;
    private static final int NAME_MAX_LENGTH = 100;

    private final Long id;
    private final String publicId;
    private final Email email;
    private final PasswordHash passwordHash;
    private final String firstName;
    private final String lastName;
    private UserStatus status;
    private final long version;
    private final Instant createdAt;
    private Instant updatedAt;

    private User(
            Long id,
            String publicId,
            Email email,
            PasswordHash passwordHash,
            String firstName,
            String lastName,
            UserStatus status,
            long version,
            Instant createdAt,
            Instant updatedAt
    ) {
        this.id = id;
        this.publicId = validatePublicId(publicId);
        this.email = Objects.requireNonNull(email, "email must not be null");
        this.passwordHash = Objects.requireNonNull(passwordHash, "password hash must not be null");
        this.firstName = validateName(firstName, "first name");
        this.lastName = validateName(lastName, "last name");
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

    public static User create(
            String publicId,
            Email email,
            PasswordHash passwordHash,
            String firstName,
            String lastName,
            Instant now
    ) {
        Instant createdAt = Objects.requireNonNull(now, "now must not be null");
        return new User(
                null,
                publicId,
                email,
                passwordHash,
                firstName,
                lastName,
                UserStatus.ACTIVE,
                0,
                createdAt,
                createdAt
        );
    }

    public static User rehydrate(
            long id,
            String publicId,
            Email email,
            PasswordHash passwordHash,
            String firstName,
            String lastName,
            UserStatus status,
            long version,
            Instant createdAt,
            Instant updatedAt
    ) {
        if (id <= 0) {
            throw new IllegalArgumentException("id must be positive");
        }
        return new User(
                id,
                publicId,
                email,
                passwordHash,
                firstName,
                lastName,
                status,
                version,
                createdAt,
                updatedAt
        );
    }

    public void lock(Instant changedAt) {
        requireStatus(UserStatus.ACTIVE, "Only an active user can be locked");
        transitionTo(UserStatus.LOCKED, changedAt);
    }

    public void disable(Instant changedAt) {
        if (status == UserStatus.DISABLED) {
            throw new IllegalStateException("User is already disabled");
        }
        transitionTo(UserStatus.DISABLED, changedAt);
    }

    private void requireStatus(UserStatus expected, String message) {
        if (status != expected) {
            throw new IllegalStateException(message);
        }
    }

    private void transitionTo(UserStatus newStatus, Instant changedAt) {
        Instant transitionTime = Objects.requireNonNull(changedAt, "changedAt must not be null");
        if (transitionTime.isBefore(updatedAt)) {
            throw new IllegalArgumentException("changedAt must not be before updatedAt");
        }
        status = newStatus;
        updatedAt = transitionTime;
    }

    private static String validatePublicId(String publicId) {
        Objects.requireNonNull(publicId, "publicId must not be null");
        if (!publicId.startsWith(PUBLIC_ID_PREFIX) || publicId.length() == PUBLIC_ID_PREFIX.length()) {
            throw new IllegalArgumentException("publicId must start with usr_ and contain an identifier");
        }
        if (publicId.length() > PUBLIC_ID_MAX_LENGTH) {
            throw new IllegalArgumentException("publicId must not exceed " + PUBLIC_ID_MAX_LENGTH + " characters");
        }
        return publicId;
    }

    private static String validateName(String name, String fieldName) {
        Objects.requireNonNull(name, fieldName + " must not be null");
        String normalized = name.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        if (normalized.length() > NAME_MAX_LENGTH) {
            throw new IllegalArgumentException(fieldName + " must not exceed " + NAME_MAX_LENGTH + " characters");
        }
        return normalized;
    }

    public Long id() {
        return id;
    }

    public String publicId() {
        return publicId;
    }

    public Email email() {
        return email;
    }

    public PasswordHash passwordHash() {
        return passwordHash;
    }

    public String firstName() {
        return firstName;
    }

    public String lastName() {
        return lastName;
    }

    public UserStatus status() {
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
