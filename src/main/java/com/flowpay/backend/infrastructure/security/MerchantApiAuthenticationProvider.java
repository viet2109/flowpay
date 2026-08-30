package com.flowpay.backend.infrastructure.security;

import com.flowpay.backend.common.error.ApiException;
import com.flowpay.backend.common.security.MerchantApiPrincipal;
import com.flowpay.backend.merchant.application.ApiKeyAuthenticationUseCase;
import com.flowpay.backend.merchant.application.AuthenticatedApiKey;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;

import java.util.List;

@RequiredArgsConstructor
public final class MerchantApiAuthenticationProvider implements AuthenticationProvider {

    private static final SimpleGrantedAuthority MERCHANT_API_AUTHORITY =
            new SimpleGrantedAuthority("ROLE_MERCHANT_API");

    private final ApiKeyAuthenticationUseCase apiKeyAuthentication;

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        BearerTokenAuthenticationToken bearerToken = (BearerTokenAuthenticationToken) authentication;
        try {
            AuthenticatedApiKey authenticated = apiKeyAuthentication.authenticate(bearerToken.getToken());
            MerchantApiPrincipal principal = new MerchantApiPrincipal(
                    authenticated.merchantPublicId(),
                    authenticated.apiKeyPublicId()
            );
            return UsernamePasswordAuthenticationToken.authenticated(
                    principal,
                    null,
                    List.of(MERCHANT_API_AUTHORITY)
            );
        } catch (ApiException exception) {
            throw new CodedAuthenticationException(
                    exception.code(),
                    exception.getMessage(),
                    exception
            );
        }
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return BearerTokenAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
