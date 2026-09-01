package com.flowpay.backend.refund.infrastructure.provider;

import com.flowpay.backend.refund.application.RefundProviderPort;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RefundProviderProperties.class)
public class RefundProviderConfiguration {

    @Bean
    RefundProviderPort refundProvider(RefundProviderProperties properties) {
        return switch (properties.provider()) {
            case SIMULATOR -> new SimulatorRefundProvider(
                    properties.simulator().defaultOutcome()
            );
        };
    }
}
