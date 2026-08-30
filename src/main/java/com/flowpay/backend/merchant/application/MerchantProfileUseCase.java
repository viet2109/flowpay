package com.flowpay.backend.merchant.application;

public interface MerchantProfileUseCase {

    MerchantProfile get(String merchantPublicId);

    MerchantProfile updateName(UpdateMerchantProfileCommand command);
}
