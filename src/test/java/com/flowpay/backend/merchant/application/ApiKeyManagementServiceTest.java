package com.flowpay.backend.merchant.application;

import com.flowpay.backend.common.error.ApiException;
import com.flowpay.backend.common.error.ErrorCode;
import com.flowpay.backend.merchant.domain.ApiKey;
import com.flowpay.backend.merchant.domain.ApiKeyStatus;
import com.flowpay.backend.merchant.domain.Merchant;
import com.flowpay.backend.merchant.domain.MerchantStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApiKeyManagementServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-30T04:00:00Z");
    private static final String RAW_KEY = "fp_test_" + "A".repeat(43);
    private static final String KEY_PREFIX = "fp_test_" + "A".repeat(12);
    private static final String KEY_HASH = "b".repeat(64);

    @Mock
    private MerchantRepository merchantRepository;

    @Mock
    private ApiKeyRepository apiKeyRepository;

    @Mock
    private ApiKeyPublicIdGenerator publicIdGenerator;

    @Mock
    private ApiKeySecretCodec secretCodec;

    private ApiKeyManagementService service;

    @BeforeEach
    void setUp() {
        service = new ApiKeyManagementService(
                merchantRepository,
                apiKeyRepository,
                publicIdGenerator,
                secretCodec,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void shouldCreateKeyAndKeepRawSecretOutOfPersistedAggregateAndText() {
        Merchant merchant = merchant();
        when(merchantRepository.findByPublicId(merchant.publicId())).thenReturn(Optional.of(merchant));
        when(publicIdGenerator.nextId()).thenReturn("key_created");
        when(secretCodec.generate()).thenReturn(new GeneratedApiKeySecret(RAW_KEY, KEY_PREFIX, KEY_HASH));
        when(apiKeyRepository.save(any(ApiKey.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CreatedApiKey created = service.create(new CreateApiKeyCommand(merchant.publicId(), " Backend "));

        ArgumentCaptor<ApiKey> persisted = ArgumentCaptor.forClass(ApiKey.class);
        verify(apiKeyRepository).save(persisted.capture());
        assertThat(persisted.getValue().name()).isEqualTo("Backend");
        assertThat(persisted.getValue().keyPrefix()).isEqualTo(KEY_PREFIX);
        assertThat(persisted.getValue().keyHash()).isEqualTo(KEY_HASH);
        assertThat(created.rawKey()).isEqualTo(RAW_KEY);
        assertThat(created.toString()).doesNotContain(RAW_KEY).doesNotContain(KEY_HASH);
    }

    @Test
    void shouldListOnlySafeApiKeySummariesForResolvedMerchant() {
        Merchant merchant = merchant();
        when(merchantRepository.findByPublicId(merchant.publicId())).thenReturn(Optional.of(merchant));
        when(apiKeyRepository.findAllByMerchantId(merchant.id())).thenReturn(List.of(activeApiKey()));

        List<ApiKeySummary> summaries = service.list(merchant.publicId());

        assertThat(summaries).containsExactly(new ApiKeySummary(
                "key_created",
                "Backend",
                KEY_PREFIX,
                ApiKeyStatus.ACTIVE,
                NOW
        ));
    }

    @Test
    void shouldRevokeOnlyKeyScopedToResolvedMerchantAndRemainIdempotent() {
        Merchant merchant = merchant();
        ApiKey apiKey = activeApiKey();
        when(merchantRepository.findByPublicId(merchant.publicId())).thenReturn(Optional.of(merchant));
        when(apiKeyRepository.findByPublicIdAndMerchantId(apiKey.publicId(), merchant.id()))
                .thenReturn(Optional.of(apiKey));
        when(apiKeyRepository.save(apiKey)).thenReturn(apiKey);

        RevokeApiKeyCommand command = new RevokeApiKeyCommand(merchant.publicId(), apiKey.publicId());
        service.revoke(command);
        service.revoke(command);

        assertThat(apiKey.status()).isEqualTo(ApiKeyStatus.REVOKED);
        assertThat(apiKey.revokedAt()).isEqualTo(NOW);
        verify(apiKeyRepository).save(apiKey);
    }

    @Test
    void shouldReturnNotFoundForCrossMerchantKeyLookup() {
        Merchant merchant = merchant();
        when(merchantRepository.findByPublicId(merchant.publicId())).thenReturn(Optional.of(merchant));
        when(apiKeyRepository.findByPublicIdAndMerchantId("key_other", merchant.id()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.revoke(
                new RevokeApiKeyCommand(merchant.publicId(), "key_other")
        )).isInstanceOfSatisfying(ApiException.class, exception -> {
            assertThat(exception.status().value()).isEqualTo(404);
            assertThat(exception.code()).isEqualTo(ErrorCode.API_KEY_NOT_FOUND);
        });
        verify(apiKeyRepository, never()).save(any());
    }

    private static Merchant merchant() {
        return Merchant.rehydrate(
                17L,
                "mrc_api_key_owner",
                "Owner Store",
                MerchantStatus.ACTIVE,
                0,
                NOW,
                NOW
        );
    }

    private static ApiKey activeApiKey() {
        return ApiKey.rehydrate(
                23L,
                "key_created",
                17L,
                "Backend",
                KEY_PREFIX,
                KEY_HASH,
                ApiKeyStatus.ACTIVE,
                null,
                null,
                null,
                NOW
        );
    }
}
