package com.flowpay.backend.merchant.application;

import com.flowpay.backend.common.error.ApiException;
import com.flowpay.backend.common.error.ErrorCode;
import com.flowpay.backend.merchant.domain.ApiKey;
import com.flowpay.backend.merchant.domain.ApiKeyStatus;
import com.flowpay.backend.merchant.domain.Merchant;
import com.flowpay.backend.merchant.domain.MerchantStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

@Service
@RequiredArgsConstructor
public class ApiKeyAuthenticationService implements ApiKeyAuthenticationUseCase {

    private final ApiKeyRepository apiKeyRepository;
    private final MerchantRepository merchantRepository;
    private final ApiKeySecretCodec secretCodec;
    private final Clock clock;

    @Override
    @Transactional
    public AuthenticatedApiKey authenticate(String rawApiKey) {
        if (!secretCodec.hasValidFormat(rawApiKey)) {
            throw invalidApiKey();
        }

        String keyPrefix = secretCodec.extractPrefix(rawApiKey);
        ApiKey apiKey = apiKeyRepository.findByKeyPrefix(keyPrefix)
                .orElseThrow(ApiKeyAuthenticationService::invalidApiKey);
        if (!secretCodec.matches(rawApiKey, apiKey.keyHash())) {
            throw invalidApiKey();
        }

        Instant authenticatedAt = clock.instant();
        if (apiKey.status() != ApiKeyStatus.ACTIVE) {
            throw apiKeyRevoked();
        }
        if (apiKey.isExpiredAt(authenticatedAt)) {
            throw invalidApiKey();
        }

        Merchant merchant = merchantRepository.findById(apiKey.merchantId())
                .orElseThrow(ApiKeyAuthenticationService::invalidApiKey);
        if (merchant.status() != MerchantStatus.ACTIVE) {
            throw merchantSuspended();
        }

        apiKey.recordLastUsed(authenticatedAt);
        apiKeyRepository.save(apiKey);
        return new AuthenticatedApiKey(merchant.publicId(), apiKey.publicId());
    }

    private static ApiException invalidApiKey() {
        return new ApiException(
                HttpStatus.UNAUTHORIZED,
                ErrorCode.INVALID_API_KEY,
                "The API key is invalid."
        );
    }

    private static ApiException apiKeyRevoked() {
        return new ApiException(
                HttpStatus.UNAUTHORIZED,
                ErrorCode.API_KEY_REVOKED,
                "The API key has been revoked."
        );
    }

    private static ApiException merchantSuspended() {
        return new ApiException(
                HttpStatus.UNAUTHORIZED,
                ErrorCode.MERCHANT_SUSPENDED,
                "The merchant cannot authenticate integration requests."
        );
    }
}
