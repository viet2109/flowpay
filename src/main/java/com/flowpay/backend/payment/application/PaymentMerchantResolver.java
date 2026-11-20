package com.flowpay.backend.payment.application;

import com.flowpay.backend.common.tenant.MerchantContext;
import com.flowpay.backend.merchant.application.ActiveMerchantSnapshot;
import com.flowpay.backend.merchant.application.MerchantAccessApi;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
@RequiredArgsConstructor
public class PaymentMerchantResolver {

    private final MerchantAccessApi merchantAccessApi;

    public ActiveMerchantSnapshot resolve(MerchantContext merchantContext) {
        MerchantContext context = Objects.requireNonNull(
                merchantContext,
                "merchantContext must not be null"
        );
        return merchantAccessApi.requireActiveMerchant(context.merchantPublicId());
    }
}
