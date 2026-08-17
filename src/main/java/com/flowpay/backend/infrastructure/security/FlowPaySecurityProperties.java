package com.flowpay.backend.infrastructure.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "flowpay.security")
public record FlowPaySecurityProperties(boolean prometheusPublic) {
}
