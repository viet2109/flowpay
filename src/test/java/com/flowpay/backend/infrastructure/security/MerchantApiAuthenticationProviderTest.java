package com.flowpay.backend.infrastructure.security;

import com.flowpay.backend.common.error.ApiException;
import com.flowpay.backend.common.error.ErrorCode;
import com.flowpay.backend.common.security.DashboardPrincipal;
import com.flowpay.backend.common.security.MerchantApiPrincipal;
import com.flowpay.backend.merchant.application.ApiKeyAuthenticationUseCase;
import com.flowpay.backend.merchant.application.AuthenticatedApiKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MerchantApiAuthenticationProviderTest {

    private static final String RAW_KEY = "fp_test_" + "A".repeat(43);

    @Mock
    private ApiKeyAuthenticationUseCase apiKeyAuthentication;

    @InjectMocks
    private MerchantApiAuthenticationProvider provider;

    @Test
    void shouldCreateDedicatedMerchantApiPrincipalAndEraseCredentials() {
        when(apiKeyAuthentication.authenticate(RAW_KEY))
                .thenReturn(new AuthenticatedApiKey("mrc_api_owner", "key_api_auth"));

        var authentication = provider.authenticate(new BearerTokenAuthenticationToken(RAW_KEY));

        assertThat(authentication).isNotNull();
        assertThat(authentication.isAuthenticated()).isTrue();
        assertThat(authentication.getPrincipal())
                .isEqualTo(new MerchantApiPrincipal("mrc_api_owner", "key_api_auth"))
                .isNotInstanceOf(DashboardPrincipal.class);
        assertThat(authentication.getAuthorities())
                .extracting("authority")
                .containsExactly("ROLE_MERCHANT_API");
        assertThat(authentication.getCredentials()).isNull();
        assertThat(authentication.toString()).doesNotContain(RAW_KEY);
    }

    @Test
    void shouldTranslateSafeApplicationFailureForProblemDetails() {
        when(apiKeyAuthentication.authenticate(RAW_KEY)).thenThrow(new ApiException(
                HttpStatus.UNAUTHORIZED,
                ErrorCode.INVALID_API_KEY,
                "The API key is invalid."
        ));

        assertThatThrownBy(() -> provider.authenticate(new BearerTokenAuthenticationToken(RAW_KEY)))
                .isInstanceOfSatisfying(CodedAuthenticationException.class, exception -> {
                    assertThat(exception.code()).isEqualTo(ErrorCode.INVALID_API_KEY);
                    assertThat(exception.toString()).doesNotContain(RAW_KEY);
                });
    }

    @Test
    void shouldOnlySupportBearerTokenAuthentication() {
        assertThat(provider.supports(BearerTokenAuthenticationToken.class)).isTrue();
        assertThat(provider.supports(Object.class)).isFalse();
    }
}
