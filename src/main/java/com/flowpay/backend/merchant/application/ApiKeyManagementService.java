package com.flowpay.backend.merchant.application;

import com.flowpay.backend.common.error.ApiException;
import com.flowpay.backend.common.error.ErrorCode;
import com.flowpay.backend.merchant.domain.ApiKey;
import com.flowpay.backend.merchant.domain.ApiKeyStatus;
import com.flowpay.backend.merchant.domain.Merchant;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ApiKeyManagementService implements ApiKeyManagementUseCase {

    private final MerchantRepository merchantRepository;
    private final ApiKeyRepository apiKeyRepository;
    private final ApiKeyPublicIdGenerator publicIdGenerator;
    private final ApiKeySecretCodec secretCodec;
    private final Clock clock;

    @Override
    @Transactional
    public CreatedApiKey create(CreateApiKeyCommand command) {
        Merchant merchant = findMerchant(command.merchantPublicId());
        GeneratedApiKeySecret secret = secretCodec.generate();
        Instant createdAt = clock.instant();
        ApiKey saved = apiKeyRepository.save(ApiKey.create(
                publicIdGenerator.nextId(),
                merchant.id(),
                command.name(),
                secret.prefix(),
                secret.digest(),
                null,
                createdAt
        ));
        return new CreatedApiKey(
                saved.publicId(),
                saved.name(),
                secret.rawKey(),
                saved.keyPrefix(),
                saved.status(),
                saved.createdAt()
        );
    }

    @Override
    @Transactional(readOnly = true)
    public List<ApiKeySummary> list(String merchantPublicId) {
        Merchant merchant = findMerchant(merchantPublicId);
        return apiKeyRepository.findAllByMerchantId(merchant.id()).stream()
                .map(ApiKeyManagementService::toSummary)
                .toList();
    }

    @Override
    @Transactional
    public void revoke(RevokeApiKeyCommand command) {
        Merchant merchant = findMerchant(command.merchantPublicId());
        ApiKey apiKey = apiKeyRepository.findByPublicIdAndMerchantId(
                        command.apiKeyPublicId(),
                        merchant.id()
                )
                .orElseThrow(ApiKeyManagementService::apiKeyNotFound);
        if (apiKey.status() == ApiKeyStatus.ACTIVE) {
            apiKey.revoke(clock.instant());
            apiKeyRepository.save(apiKey);
        }
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

    private static ApiKeySummary toSummary(ApiKey apiKey) {
        return new ApiKeySummary(
                apiKey.publicId(),
                apiKey.name(),
                apiKey.keyPrefix(),
                apiKey.status(),
                apiKey.createdAt()
        );
    }

    private static ApiException apiKeyNotFound() {
        return new ApiException(
                HttpStatus.NOT_FOUND,
                ErrorCode.API_KEY_NOT_FOUND,
                "The API key was not found."
        );
    }
}
