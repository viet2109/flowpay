package com.flowpay.backend.merchant.application;

import com.flowpay.backend.merchant.domain.Merchant;
import com.flowpay.backend.merchant.domain.MerchantMember;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class MerchantMembershipService implements MerchantMembershipApi {

    private final MerchantMemberRepository memberRepository;
    private final MerchantRepository merchantRepository;

    @Override
    @Transactional(readOnly = true)
    public Optional<Membership> findForUser(long userId) {
        if (userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }

        return memberRepository.findFirstByUserId(userId)
                .flatMap(member -> toMembership(member, merchantRepository.findById(member.merchantId())));
    }

    private static Optional<Membership> toMembership(
            MerchantMember member,
            Optional<Merchant> merchant
    ) {
        return merchant.map(value -> new Membership(value.publicId(), member.role()));
    }
}
