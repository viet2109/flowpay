package com.flowpay.backend.merchant.application;

import com.flowpay.backend.merchant.domain.Merchant;

import java.util.Optional;

public interface MerchantRepository {

    Merchant save(Merchant merchant);

    Optional<Merchant> findById(long id);

    Optional<Merchant> findByPublicId(String publicId);
}
