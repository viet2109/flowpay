package com.flowpay.backend.ledger.domain;

import java.time.Instant;
import java.util.Currency;
import java.util.Objects;

public final class LedgerAccount {

    private static final String PUBLIC_ID_PREFIX = "la_";
    private static final int PUBLIC_ID_MAX_LENGTH = 64;

    private final Long internalId;
    private final String publicId;
    private final LedgerAccountCode accountCode;
    private final LedgerAccountType accountType;
    private final LedgerOwnerType ownerType;
    private final Long ownerId;
    private final Currency currency;
    private final LedgerAccountStatus status;
    private final Instant createdAt;

    private LedgerAccount(
            Long internalId,
            String publicId,
            String persistedAccountCode,
            LedgerAccountType accountType,
            LedgerOwnerType ownerType,
            Long ownerId,
            Currency currency,
            LedgerAccountStatus status,
            Instant createdAt
    ) {
        this.internalId = validateInternalId(internalId);
        this.publicId = validatePublicId(publicId);
        this.accountType = Objects.requireNonNull(accountType, "accountType must not be null");
        this.ownerType = Objects.requireNonNull(ownerType, "ownerType must not be null");
        this.currency = Objects.requireNonNull(currency, "currency must not be null");
        this.ownerId = validateOwner(accountType, ownerType, ownerId);
        this.accountCode = expectedAccountCode(accountType, this.ownerId, this.currency);
        validatePersistedAccountCode(persistedAccountCode, this.accountCode);
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    public static LedgerAccount createSystemClearing(
            String publicId,
            Currency currency,
            Instant createdAt
    ) {
        return new LedgerAccount(
                null,
                publicId,
                null,
                LedgerAccountType.SYSTEM_CLEARING,
                LedgerOwnerType.SYSTEM,
                null,
                currency,
                LedgerAccountStatus.ACTIVE,
                createdAt
        );
    }

    public static LedgerAccount createMerchantPayable(
            String publicId,
            long merchantInternalId,
            Currency currency,
            Instant createdAt
    ) {
        return new LedgerAccount(
                null,
                publicId,
                null,
                LedgerAccountType.MERCHANT_PAYABLE,
                LedgerOwnerType.MERCHANT,
                merchantInternalId,
                currency,
                LedgerAccountStatus.ACTIVE,
                createdAt
        );
    }

    public static LedgerAccount rehydrate(
            long internalId,
            String publicId,
            String accountCode,
            LedgerAccountType accountType,
            LedgerOwnerType ownerType,
            Long ownerId,
            Currency currency,
            LedgerAccountStatus status,
            Instant createdAt
    ) {
        return new LedgerAccount(
                internalId,
                publicId,
                Objects.requireNonNull(accountCode, "accountCode must not be null"),
                accountType,
                ownerType,
                ownerId,
                currency,
                status,
                createdAt
        );
    }

    private static Long validateInternalId(Long internalId) {
        if (internalId != null && internalId <= 0) {
            throw new IllegalArgumentException("internalId must be positive");
        }
        return internalId;
    }

    private static String validatePublicId(String publicId) {
        Objects.requireNonNull(publicId, "publicId must not be null");
        if (!publicId.startsWith(PUBLIC_ID_PREFIX) || publicId.length() == PUBLIC_ID_PREFIX.length()) {
            throw new IllegalArgumentException("publicId must start with la_ and contain an identifier");
        }
        if (publicId.length() > PUBLIC_ID_MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "publicId must not exceed " + PUBLIC_ID_MAX_LENGTH + " characters"
            );
        }
        return publicId;
    }

    private static Long validateOwner(
            LedgerAccountType accountType,
            LedgerOwnerType ownerType,
            Long ownerId
    ) {
        return switch (accountType) {
            case SYSTEM_CLEARING -> {
                if (ownerType != LedgerOwnerType.SYSTEM || ownerId != null) {
                    throw new IllegalArgumentException(
                            "SYSTEM_CLEARING account must have SYSTEM owner without ownerId"
                    );
                }
                yield null;
            }
            case MERCHANT_PAYABLE -> {
                if (ownerType != LedgerOwnerType.MERCHANT || ownerId == null || ownerId <= 0) {
                    throw new IllegalArgumentException(
                            "MERCHANT_PAYABLE account must have a positive MERCHANT ownerId"
                    );
                }
                yield ownerId;
            }
        };
    }

    private static LedgerAccountCode expectedAccountCode(
            LedgerAccountType accountType,
            Long ownerId,
            Currency currency
    ) {
        return switch (accountType) {
            case SYSTEM_CLEARING -> LedgerAccountCode.systemClearing(currency);
            case MERCHANT_PAYABLE -> LedgerAccountCode.merchantPayable(ownerId, currency);
        };
    }

    private static void validatePersistedAccountCode(
            String persistedAccountCode,
            LedgerAccountCode expectedAccountCode
    ) {
        if (persistedAccountCode != null
                && !expectedAccountCode.value().equals(persistedAccountCode)) {
            throw new IllegalArgumentException("accountCode does not match account identity");
        }
    }

    public Long internalId() {
        return internalId;
    }

    public String publicId() {
        return publicId;
    }

    public LedgerAccountCode accountCode() {
        return accountCode;
    }

    public LedgerAccountType accountType() {
        return accountType;
    }

    public LedgerOwnerType ownerType() {
        return ownerType;
    }

    public Long ownerId() {
        return ownerId;
    }

    public Currency currency() {
        return currency;
    }

    public LedgerAccountStatus status() {
        return status;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
