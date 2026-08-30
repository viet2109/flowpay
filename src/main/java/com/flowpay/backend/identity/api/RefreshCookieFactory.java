package com.flowpay.backend.identity.api;

import com.flowpay.backend.identity.application.IssuedRefreshToken;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;

@Component
@RequiredArgsConstructor
public class RefreshCookieFactory {

    static final String COOKIE_NAME = "flowpay_refresh";
    private static final String COOKIE_PATH = "/api/v1/auth";

    private final RefreshCookieProperties properties;
    private final Clock clock;

    ResponseCookie issue(IssuedRefreshToken token) {
        Duration maxAge = Duration.between(clock.instant(), token.expiresAt());
        return base(token.value())
                .maxAge(maxAge.isNegative() ? Duration.ZERO : maxAge)
                .build();
    }

    ResponseCookie clear() {
        return base("").maxAge(Duration.ZERO).build();
    }

    private ResponseCookie.ResponseCookieBuilder base(String value) {
        return ResponseCookie.from(COOKIE_NAME, value)
                .httpOnly(true)
                .secure(properties.cookieSecure())
                .sameSite("Lax")
                .path(COOKIE_PATH);
    }
}
