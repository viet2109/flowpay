package com.flowpay.backend.merchant.application;

import com.flowpay.backend.common.error.ApiException;
import com.flowpay.backend.common.error.ErrorCode;
import com.flowpay.backend.merchant.domain.Merchant;
import com.flowpay.backend.merchant.domain.MerchantStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MerchantAccessService implements MerchantAccessApi {

    private final MerchantRepository merchantRepository;

    @Override
    @Transactional(readOnly = true)
    public ActiveMerchantSnapshot requireActiveMerchant(String merchantPublicId) {
        if (merchantPublicId == null || merchantPublicId.isBlank()) {
            throw new IllegalArgumentException("merchantPublicId must not be blank");
        }

        Merchant merchant = merchantRepository.findByPublicId(merchantPublicId)
                .orElseThrow(MerchantAccessService::merchantNotFound);
        if (merchant.status() != MerchantStatus.ACTIVE) {
            throw merchantInactive();
        }
        return new ActiveMerchantSnapshot(merchant.id(), merchant.publicId());
    }

    private static ApiException merchantNotFound() {
        return new ApiException(
                HttpStatus.NOT_FOUND,
                ErrorCode.MERCHANT_NOT_FOUND,
                "The merchant was not found."
        );
    }

    private static ApiException merchantInactive() {
        return new ApiException(
                HttpStatus.FORBIDDEN,
                ErrorCode.MERCHANT_SUSPENDED,
                "The merchant is not active."
        );
    }
}
