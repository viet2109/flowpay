package com.flowpay.backend.merchant.application;

import com.flowpay.backend.common.error.ApiException;
import com.flowpay.backend.common.error.ErrorCode;
import com.flowpay.backend.merchant.domain.Merchant;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

@Service
@RequiredArgsConstructor
public class MerchantProfileService implements MerchantProfileUseCase {

    private final MerchantRepository merchantRepository;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public MerchantProfile get(String merchantPublicId) {
        return toProfile(findMerchant(merchantPublicId));
    }

    @Override
    @Transactional
    public MerchantProfile updateName(UpdateMerchantProfileCommand command) {
        Merchant merchant = findMerchant(command.merchantPublicId());
        merchant.updateName(command.name(), clock.instant());
        return toProfile(merchantRepository.save(merchant));
    }

    private Merchant findMerchant(String merchantPublicId) {
        if (merchantPublicId == null || merchantPublicId.isBlank()) {
            throw new IllegalArgumentException("merchantPublicId must not be blank");
        }
        return merchantRepository.findByPublicId(merchantPublicId)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND,
                        ErrorCode.MERCHANT_NOT_FOUND,
                        "The merchant was not found."
                ));
    }

    private static MerchantProfile toProfile(Merchant merchant) {
        return new MerchantProfile(
                merchant.publicId(),
                merchant.name(),
                merchant.status(),
                merchant.createdAt()
        );
    }
}
