package com.flowpay.backend.merchant.application;

/**
 * Public module API for resolving an active merchant without exposing Merchant persistence details.
 */
public interface MerchantAccessApi {

    ActiveMerchantSnapshot requireActiveMerchant(String merchantPublicId);
}
