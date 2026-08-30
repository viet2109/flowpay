package com.flowpay.backend.identity.api;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RefreshCookieProperties.class)
class AuthApiConfiguration {
}
