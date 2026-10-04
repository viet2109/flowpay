package com.flowpay.backend.webhook.infrastructure.url;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "flowpay.webhook.url-policy")
public record WebhookUrlPolicyProperties(boolean allowInsecureLocalhost) {
}
