package com.flowpay.backend.webhook.infrastructure.url;

import com.flowpay.backend.webhook.application.WebhookUrlPolicy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WebhookUrlPolicyProperties.class)
class WebhookUrlPolicyConfiguration {

    @Bean
    WebhookUrlPolicy webhookUrlPolicy(WebhookUrlPolicyProperties properties) {
        return new DefaultWebhookUrlPolicy(properties.allowInsecureLocalhost());
    }
}
