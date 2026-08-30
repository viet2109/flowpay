package com.flowpay.backend.payment.application;

import com.flowpay.backend.common.security.MerchantApiPrincipal;
import com.flowpay.backend.merchant.application.ActiveMerchantSnapshot;
import com.flowpay.backend.merchant.application.MerchantAccessApi;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
@RequiredArgsConstructor
public class PaymentMerchantResolver {

    private final MerchantAccessApi merchantAccessApi;

    public ActiveMerchantSnapshot resolve(MerchantApiPrincipal principal) {
        MerchantApiPrincipal authenticatedPrincipal = Objects.requireNonNull(
                principal,
                "principal must not be null"
        );
        return merchantAccessApi.requireActiveMerchant(authenticatedPrincipal.merchantPublicId());
    }
}
