package com.flowpay.backend.merchant.infrastructure.persistence;

import com.flowpay.backend.merchant.application.MerchantMemberRepository;
import com.flowpay.backend.merchant.domain.MerchantMember;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class JpaMerchantMemberRepositoryAdapter implements MerchantMemberRepository {

    private final SpringDataMerchantMemberRepository repository;

    public JpaMerchantMemberRepositoryAdapter(SpringDataMerchantMemberRepository repository) {
        this.repository = repository;
    }

    @Override
    public MerchantMember add(MerchantMember member) {
        MerchantMemberEntity saved = repository.saveAndFlush(toEntity(member));
        return toDomain(saved);
    }

    @Override
    public Optional<MerchantMember> findByMerchantIdAndUserId(long merchantId, long userId) {
        return repository.findByIdMerchantIdAndIdUserId(merchantId, userId)
                .map(JpaMerchantMemberRepositoryAdapter::toDomain);
    }

    private static MerchantMemberEntity toEntity(MerchantMember member) {
        return new MerchantMemberEntity(
                member.merchantId(),
                member.userId(),
                member.role(),
                member.createdAt()
        );
    }

    private static MerchantMember toDomain(MerchantMemberEntity entity) {
        return MerchantMember.rehydrate(
                entity.merchantId(),
                entity.userId(),
                entity.role(),
                entity.createdAt()
        );
    }
}
