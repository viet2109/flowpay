package com.flowpay.backend.merchant.domain;

import java.time.Instant;
import java.util.Objects;

public final class MerchantMember {

    private final long merchantId;
    private final long userId;
    private final MerchantRole role;
    private final Instant createdAt;

    private MerchantMember(long merchantId, long userId, MerchantRole role, Instant createdAt) {
        if (merchantId <= 0) {
            throw new IllegalArgumentException("merchantId must be positive");
        }
        if (userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        this.merchantId = merchantId;
        this.userId = userId;
        this.role = Objects.requireNonNull(role, "role must not be null");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    public static MerchantMember createOwner(long merchantId, long userId, Instant createdAt) {
        return new MerchantMember(merchantId, userId, MerchantRole.OWNER, createdAt);
    }

    public static MerchantMember rehydrate(
            long merchantId,
            long userId,
            MerchantRole role,
            Instant createdAt
    ) {
        return new MerchantMember(merchantId, userId, role, createdAt);
    }

    public long merchantId() {
        return merchantId;
    }

    public long userId() {
        return userId;
    }

    public MerchantRole role() {
        return role;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
