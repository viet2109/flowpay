package com.flowpay.backend.merchant.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MerchantTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-19T03:00:00Z");

    @Test
    void shouldCreateActiveMerchant() {
        Merchant merchant = Merchant.create("mrc_01K2P1T03TEST", " ABC Store ", CREATED_AT);

        assertThat(merchant.id()).isNull();
        assertThat(merchant.publicId()).isEqualTo("mrc_01K2P1T03TEST");
        assertThat(merchant.name()).isEqualTo("ABC Store");
        assertThat(merchant.status()).isEqualTo(MerchantStatus.ACTIVE);
        assertThat(merchant.version()).isZero();
        assertThat(merchant.createdAt()).isEqualTo(CREATED_AT);
        assertThat(merchant.updatedAt()).isEqualTo(CREATED_AT);
    }

    @Test
    void shouldCreateOwnerMembershipUsingOnlyIds() {
        MerchantMember member = MerchantMember.createOwner(10L, 20L, CREATED_AT);

        assertThat(member.merchantId()).isEqualTo(10L);
        assertThat(member.userId()).isEqualTo(20L);
        assertThat(member.role()).isEqualTo(MerchantRole.OWNER);
        assertThat(member.createdAt()).isEqualTo(CREATED_AT);
    }

    @Test
    void shouldPrepareAllSpecifiedMerchantRoles() {
        assertThat(MerchantRole.values()).containsExactly(
                MerchantRole.OWNER,
                MerchantRole.ADMIN,
                MerchantRole.DEVELOPER,
                MerchantRole.FINANCE,
                MerchantRole.VIEWER
        );
    }

    @Test
    void shouldRejectInvalidMerchantPublicId() {
        assertThatThrownBy(() -> Merchant.create("usr_01K", "ABC Store", CREATED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("publicId must start with mrc_ and contain an identifier");
    }

    @Test
    void shouldUpdateMerchantNameThroughDomainBehavior() {
        Merchant merchant = Merchant.create("mrc_01K2P1T03TEST", "ABC Store", CREATED_AT);
        Instant updatedAt = CREATED_AT.plusSeconds(60);

        merchant.updateName(" ABC Technology Store ", updatedAt);

        assertThat(merchant.name()).isEqualTo("ABC Technology Store");
        assertThat(merchant.updatedAt()).isEqualTo(updatedAt);
    }

    @Test
    void shouldRejectInvalidMerchantNameUpdate() {
        Merchant merchant = Merchant.create("mrc_01K2P1T03TEST", "ABC Store", CREATED_AT);

        assertThatThrownBy(() -> merchant.updateName("   ", CREATED_AT.plusSeconds(60)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("name must not be blank");
        assertThatThrownBy(() -> merchant.updateName("Valid name", CREATED_AT.minusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("updatedAt must not be before the current updatedAt");
    }
}
