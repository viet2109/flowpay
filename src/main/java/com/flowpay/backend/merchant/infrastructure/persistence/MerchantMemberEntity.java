package com.flowpay.backend.merchant.infrastructure.persistence;

import com.flowpay.backend.merchant.domain.MerchantRole;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.springframework.data.domain.Persistable;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

@Entity(name = "MerchantMemberEntity")
@Table(name = "merchant_members")
class MerchantMemberEntity implements Persistable<MerchantMemberEntity.MemberId> {

    @EmbeddedId
    private MemberId id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private MerchantRole role;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected MerchantMemberEntity() {
    }

    MerchantMemberEntity(long merchantId, long userId, MerchantRole role, Instant createdAt) {
        this.id = new MemberId(merchantId, userId);
        this.role = role;
        this.createdAt = createdAt;
    }

    @Override
    public MemberId getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return true;
    }

    long merchantId() {
        return id.merchantId;
    }

    long userId() {
        return id.userId;
    }

    MerchantRole role() {
        return role;
    }

    Instant createdAt() {
        return createdAt;
    }

    @Embeddable
    static class MemberId implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        @Column(name = "merchant_id", nullable = false)
        private Long merchantId;

        @Column(name = "user_id", nullable = false)
        private Long userId;

        protected MemberId() {
        }

        MemberId(long merchantId, long userId) {
            this.merchantId = merchantId;
            this.userId = userId;
        }

        @Override
        public boolean equals(Object other) {
            return this == other
                    || other instanceof MemberId that
                    && Objects.equals(merchantId, that.merchantId)
                    && Objects.equals(userId, that.userId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(merchantId, userId);
        }
    }
}
