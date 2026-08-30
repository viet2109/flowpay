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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApiKeyAuthenticationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-30T05:00:00Z");
    private static final Instant CREATED_AT = NOW.minusSeconds(3600);
    private static final String RAW_KEY = "fp_test_" + "A".repeat(43);
    private static final String KEY_PREFIX = "fp_test_" + "A".repeat(12);
    private static final String KEY_HASH = "b".repeat(64);

    @Mock
    private ApiKeyRepository apiKeyRepository;

    @Mock
    private MerchantRepository merchantRepository;

    @Mock
    private ApiKeySecretCodec secretCodec;

    private ApiKeyAuthenticationService service;

    @BeforeEach
    void setUp() {
        service = new ApiKeyAuthenticationService(
                apiKeyRepository,
                merchantRepository,
                secretCodec,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void shouldAuthenticateValidKeyAndRecordLastUse() {
        ApiKey apiKey = apiKey(ApiKeyStatus.ACTIVE, null, null);
        Merchant merchant = merchant(MerchantStatus.ACTIVE);
        arrangeValidLookup(apiKey);
        when(merchantRepository.findById(apiKey.merchantId())).thenReturn(Optional.of(merchant));
        when(apiKeyRepository.save(apiKey)).thenReturn(apiKey);

        AuthenticatedApiKey authenticated = service.authenticate(RAW_KEY);

        assertThat(authenticated).isEqualTo(new AuthenticatedApiKey(merchant.publicId(), apiKey.publicId()));
        assertThat(authenticated.toString()).doesNotContain(RAW_KEY).doesNotContain(KEY_HASH);
        assertThat(apiKey.lastUsedAt()).isEqualTo(NOW);
        verify(apiKeyRepository).save(apiKey);
    }

    @Test
    void shouldRejectMalformedKeyBeforeLookup() {
        when(secretCodec.hasValidFormat(RAW_KEY)).thenReturn(false);

        assertFailure(ErrorCode.INVALID_API_KEY);

        verify(apiKeyRepository, never()).findByKeyPrefix(any());
        verify(secretCodec, never()).extractPrefix(any());
    }

    @Test
    void shouldRejectUnknownPrefixWithoutDigestVerification() {
        when(secretCodec.hasValidFormat(RAW_KEY)).thenReturn(true);
        when(secretCodec.extractPrefix(RAW_KEY)).thenReturn(KEY_PREFIX);
        when(apiKeyRepository.findByKeyPrefix(KEY_PREFIX)).thenReturn(Optional.empty());

        assertFailure(ErrorCode.INVALID_API_KEY);

        verify(secretCodec, never()).matches(any(), any());
    }

    @Test
    void shouldRejectModifiedSecretAfterCompleteDigestComparison() {
        ApiKey apiKey = apiKey(ApiKeyStatus.ACTIVE, null, null);
        when(secretCodec.hasValidFormat(RAW_KEY)).thenReturn(true);
        when(secretCodec.extractPrefix(RAW_KEY)).thenReturn(KEY_PREFIX);
        when(apiKeyRepository.findByKeyPrefix(KEY_PREFIX)).thenReturn(Optional.of(apiKey));
        when(secretCodec.matches(RAW_KEY, KEY_HASH)).thenReturn(false);

        assertFailure(ErrorCode.INVALID_API_KEY);

        verify(merchantRepository, never()).findById(anyLong());
        verify(apiKeyRepository, never()).save(any());
    }

    @Test
    void shouldRejectRevokedKey() {
        ApiKey apiKey = apiKey(ApiKeyStatus.REVOKED, CREATED_AT.plusSeconds(300), null);
        arrangeValidLookup(apiKey);

        assertFailure(ErrorCode.API_KEY_REVOKED);

        verify(merchantRepository, never()).findById(anyLong());
        verify(apiKeyRepository, never()).save(any());
    }

    @Test
    void shouldRejectExpiredKeyAsInvalid() {
        ApiKey apiKey = apiKey(ApiKeyStatus.ACTIVE, null, NOW);
        arrangeValidLookup(apiKey);

        assertFailure(ErrorCode.INVALID_API_KEY);

        verify(merchantRepository, never()).findById(any(Long.class));
        verify(apiKeyRepository, never()).save(any());
    }

    @Test
    void shouldRejectSuspendedOrClosedMerchant() {
        for (MerchantStatus status : new MerchantStatus[]{MerchantStatus.SUSPENDED, MerchantStatus.CLOSED}) {
            ApiKey apiKey = apiKey(ApiKeyStatus.ACTIVE, null, null);
            arrangeValidLookup(apiKey);
            when(merchantRepository.findById(apiKey.merchantId()))
                    .thenReturn(Optional.of(merchant(status)));

            assertFailure(ErrorCode.MERCHANT_SUSPENDED);
        }

        verify(apiKeyRepository, never()).save(any());
    }

    @Test
    void shouldKeepRawKeyAndDigestOutOfAuthenticationErrors() {
        when(secretCodec.hasValidFormat(RAW_KEY)).thenReturn(false);

        assertThatThrownBy(() -> service.authenticate(RAW_KEY))
                .isInstanceOfSatisfying(ApiException.class, exception -> assertThat(exception.toString())
                        .doesNotContain(RAW_KEY)
                        .doesNotContain(KEY_HASH));
    }

    private void arrangeValidLookup(ApiKey apiKey) {
        when(secretCodec.hasValidFormat(RAW_KEY)).thenReturn(true);
        when(secretCodec.extractPrefix(RAW_KEY)).thenReturn(KEY_PREFIX);
        when(apiKeyRepository.findByKeyPrefix(KEY_PREFIX)).thenReturn(Optional.of(apiKey));
        when(secretCodec.matches(RAW_KEY, KEY_HASH)).thenReturn(true);
    }

    private void assertFailure(ErrorCode expectedCode) {
        assertThatThrownBy(() -> service.authenticate(RAW_KEY))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.status().value()).isEqualTo(401);
                    assertThat(exception.code()).isEqualTo(expectedCode);
                    assertThat(exception.toString()).doesNotContain(RAW_KEY).doesNotContain(KEY_HASH);
                });
    }

    private static Merchant merchant(MerchantStatus status) {
        return Merchant.rehydrate(
                17L,
                "mrc_api_auth_owner",
                "API Auth Store",
                status,
                0,
                CREATED_AT,
                CREATED_AT
        );
    }

    private static ApiKey apiKey(ApiKeyStatus status, Instant revokedAt, Instant expiresAt) {
        return ApiKey.rehydrate(
                23L,
                "key_api_auth",
                17L,
                "Backend",
                KEY_PREFIX,
                KEY_HASH,
                status,
                null,
                expiresAt,
                revokedAt,
                CREATED_AT
        );
    }
}
