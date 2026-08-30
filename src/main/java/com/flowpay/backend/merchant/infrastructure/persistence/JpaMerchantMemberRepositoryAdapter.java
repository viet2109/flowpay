package com.flowpay.backend.merchant.infrastructure.persistence;

import com.flowpay.backend.merchant.application.MerchantMemberRepository;
import com.flowpay.backend.merchant.domain.MerchantMember;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class JpaMerchantMemberRepositoryAdapter implements MerchantMemberRepository {

    private final SpringDataMerchantMemberRepository repository;

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

    @Override
    public Optional<MerchantMember> findFirstByUserId(long userId) {
        return repository.findFirstByIdUserIdOrderByCreatedAtAscIdMerchantIdAsc(userId)
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
