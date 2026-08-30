package com.flowpay.backend.merchant.application;

import com.flowpay.backend.merchant.domain.MerchantRole;

import java.util.Objects;
import java.util.Optional;

public interface MerchantMembershipApi {

    Optional<Membership> findForUser(long userId);

    record Membership(String merchantPublicId, MerchantRole role) {

        public Membership {
            Objects.requireNonNull(merchantPublicId, "merchantPublicId must not be null");
            Objects.requireNonNull(role, "role must not be null");
        }
    }
}
