package com.flowpay.backend.merchant.application;

import com.flowpay.backend.merchant.domain.Merchant;
import com.flowpay.backend.merchant.domain.MerchantMember;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class MerchantOnboardingService implements MerchantOnboardingApi {

    private final MerchantRepository merchantRepository;
    private final MerchantMemberRepository memberRepository;
    private final MerchantPublicIdGenerator publicIdGenerator;

    @Override
    @Transactional
    public Result onboard(Command command) {
        Instant now = Instant.now();
        Merchant merchant = Merchant.create(
                publicIdGenerator.nextId(),
                command.merchantName(),
                now
        );
        Merchant savedMerchant = merchantRepository.save(merchant);
        MerchantMember owner = memberRepository.add(MerchantMember.createOwner(
                savedMerchant.id(),
                command.userId(),
                now
        ));

        return new Result(
                savedMerchant.publicId(),
                savedMerchant.name(),
                savedMerchant.status(),
                owner.role()
        );
    }
}
