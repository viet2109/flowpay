package com.flowpay.backend.merchant.application;

import com.flowpay.backend.merchant.domain.MerchantMember;

import java.util.Optional;

public interface MerchantMemberRepository {

    MerchantMember add(MerchantMember member);

    Optional<MerchantMember> findByMerchantIdAndUserId(long merchantId, long userId);
}
