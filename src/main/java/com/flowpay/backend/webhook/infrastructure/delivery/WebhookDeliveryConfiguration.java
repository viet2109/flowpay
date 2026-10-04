package com.flowpay.backend.webhook.infrastructure.delivery;

import com.flowpay.backend.webhook.application.WebhookDeliveryRetryProperties;
import com.flowpay.backend.webhook.application.WebhookDeliveryWorkerProperties;
import com.flowpay.backend.webhook.infrastructure.http.WebhookHttpProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({WebhookDeliveryWorkerProperties.class, WebhookDeliveryRetryProperties.class})
public class WebhookDeliveryConfiguration {
    public WebhookDeliveryConfiguration(WebhookDeliveryWorkerProperties worker, WebhookHttpProperties http) {
        if (worker.leaseTimeout().compareTo(http.requestTimeout()) <= 0) {
            throw new IllegalArgumentException("Webhook delivery lease must exceed the HTTP request timeout");
        }
    }
}
