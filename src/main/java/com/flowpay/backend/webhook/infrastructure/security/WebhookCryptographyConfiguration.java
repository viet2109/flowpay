package com.flowpay.backend.webhook.infrastructure.security;

import com.flowpay.backend.webhook.application.WebhookSecretCipher;
import com.flowpay.backend.webhook.application.WebhookSecretGenerator;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WebhookSecretProperties.class)
class WebhookCryptographyConfiguration {

    @Bean
    WebhookSecretGenerator webhookSecretGenerator() {
        return new SecureWebhookSecretGenerator();
    }

    @Bean
    WebhookSecretCipher webhookSecretCipher(WebhookSecretProperties properties) {
        return new AesGcmWebhookSecretCipher(properties.encryptionKey());
    }
}
