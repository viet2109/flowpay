package com.flowpay.backend.infrastructure.security;

import com.flowpay.backend.common.security.DashboardPrincipal;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public final class DashboardJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        try {
            DashboardPrincipal principal = new DashboardPrincipal(
                    jwt.getSubject(),
                    jwt.getClaimAsString("merchant"),
                    jwt.getClaimAsString("role")
            );
            return UsernamePasswordAuthenticationToken.authenticated(
                    principal,
                    null,
                    List.of(new SimpleGrantedAuthority("ROLE_" + principal.role()))
            );
        } catch (IllegalArgumentException exception) {
            OAuth2Error error = new OAuth2Error(
                    OAuth2ErrorCodes.INVALID_TOKEN,
                    "The access token does not contain valid dashboard claims.",
                    null
            );
            throw new OAuth2AuthenticationException(error, error.getDescription(), exception);
        }
    }
}
