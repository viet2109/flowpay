package com.flowpay.backend.identity.api;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "flowpay.security.refresh-cookie")
public record RefreshCookieProperties(boolean cookieSecure) {
}
