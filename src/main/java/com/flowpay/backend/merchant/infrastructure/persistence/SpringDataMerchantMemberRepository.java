package com.flowpay.backend.merchant.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

interface SpringDataMerchantMemberRepository
        extends JpaRepository<MerchantMemberEntity, MerchantMemberEntity.MemberId> {

    Optional<MerchantMemberEntity> findByIdMerchantIdAndIdUserId(long merchantId, long userId);
}
