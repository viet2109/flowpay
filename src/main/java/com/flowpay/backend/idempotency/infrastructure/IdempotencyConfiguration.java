package com.flowpay.backend.idempotency.infrastructure;

import com.flowpay.backend.idempotency.application.IdempotencyProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(IdempotencyProperties.class)
public class IdempotencyConfiguration {
}
