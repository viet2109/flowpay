package com.flowpay.backend.infrastructure.security;

import com.flowpay.backend.common.security.DashboardPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jwt.Jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DashboardJwtAuthenticationConverterTest {

    private final DashboardJwtAuthenticationConverter converter = new DashboardJwtAuthenticationConverter();

    @Test
    void shouldCreateExplicitPrincipalAndRoleAuthority() {
        Jwt jwt = jwt("usr_01KUSER", "mrc_01KMERCHANT", "OWNER");

        var authentication = converter.convert(jwt);

        assertThat(authentication).isNotNull();
        assertThat(authentication.isAuthenticated()).isTrue();
        assertThat(authentication.getPrincipal()).isEqualTo(
                new DashboardPrincipal("usr_01KUSER", "mrc_01KMERCHANT", "OWNER")
        );
        assertThat(authentication.getAuthorities())
                .extracting("authority")
                .containsExactly("ROLE_OWNER");
        assertThat(authentication.getCredentials()).isNull();
    }

    @Test
    void shouldRejectJwtWithoutRequiredDashboardClaims() {
        Jwt jwt = jwt("usr_01KUSER", null, "OWNER");

        assertThatThrownBy(() -> converter.convert(jwt))
                .isInstanceOf(OAuth2AuthenticationException.class)
                .hasMessageContaining("valid dashboard claims");
    }

    private static Jwt jwt(String subject, String merchantPublicId, String role) {
        Jwt.Builder builder = Jwt.withTokenValue("signed-token")
                .header("alg", "RS256")
                .subject(subject)
                .claim("role", role);
        if (merchantPublicId != null) {
            builder.claim("merchant", merchantPublicId);
        }
        return builder.build();
    }
}
