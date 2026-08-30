package com.flowpay.backend.identity.api;

import com.flowpay.backend.identity.application.IssuedRefreshToken;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseCookie;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class RefreshCookieFactoryTest {

    private static final Instant NOW = Instant.parse("2026-08-30T01:00:00Z");

    @Test
    void shouldCreateProductionSafeRefreshCookie() {
        RefreshCookieFactory factory = new RefreshCookieFactory(
                new RefreshCookieProperties(true),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );

        ResponseCookie cookie = factory.issue(new IssuedRefreshToken("opaque-token", NOW.plusSeconds(604800)));

        assertThat(cookie.getName()).isEqualTo("flowpay_refresh");
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.isSecure()).isTrue();
        assertThat(cookie.getSameSite()).isEqualTo("Lax");
        assertThat(cookie.getPath()).isEqualTo("/api/v1/auth");
        assertThat(cookie.getMaxAge().getSeconds()).isEqualTo(604800);
        assertThat(cookie.toString()).contains("opaque-token");
    }

    @Test
    void shouldClearRefreshCookie() {
        RefreshCookieFactory factory = new RefreshCookieFactory(
                new RefreshCookieProperties(false),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );

        ResponseCookie cookie = factory.clear();

        assertThat(cookie.getValue()).isEmpty();
        assertThat(cookie.getMaxAge().isZero()).isTrue();
        assertThat(cookie.isHttpOnly()).isTrue();
    }

    @Test
    void shouldRedactAccessTokensFromResponseDiagnostics() {
        String rawAccessToken = "header.payload.signature";

        LoginResponse login = new LoginResponse(
                rawAccessToken,
                900,
                new LoginResponse.UserView("usr_public", "viet@example.com")
        );
        RefreshResponse refresh = new RefreshResponse(rawAccessToken, 900);

        assertThat(login.toString()).doesNotContain(rawAccessToken).contains("[REDACTED]");
        assertThat(refresh.toString()).doesNotContain(rawAccessToken).contains("[REDACTED]");
    }
}
