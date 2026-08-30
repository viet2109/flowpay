package com.flowpay.backend.merchant.application;

import com.flowpay.backend.merchant.domain.MerchantRole;
import com.flowpay.backend.merchant.domain.MerchantStatus;

import java.util.Objects;

public interface MerchantOnboardingApi {

    Result onboard(Command command);

    record Command(long userId, String merchantName) {

        public Command {
            if (userId <= 0) {
                throw new IllegalArgumentException("userId must be positive");
            }
            Objects.requireNonNull(merchantName, "merchantName must not be null");
        }
    }

    record Result(
            String merchantPublicId,
            String merchantName,
            MerchantStatus status,
            MerchantRole initialRole
    ) {

        public Result {
            Objects.requireNonNull(merchantPublicId, "merchantPublicId must not be null");
            Objects.requireNonNull(merchantName, "merchantName must not be null");
            Objects.requireNonNull(status, "status must not be null");
            Objects.requireNonNull(initialRole, "initialRole must not be null");
        }
    }
}
