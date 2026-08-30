package com.flowpay.backend.payment.infrastructure.provider;

import com.flowpay.backend.payment.application.PaymentProviderPort;
import com.flowpay.backend.payment.application.PaymentProviderSelection;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PaymentProviderProperties.class)
public class PaymentProviderConfiguration {

    @Bean
    PaymentProviderPort paymentProvider(PaymentProviderProperties properties) {
        return switch (properties.provider()) {
            case SIMULATOR -> new SimulatorPaymentProvider(
                    properties.simulator().defaultOutcome()
            );
        };
    }

    @Bean
    PaymentProviderSelection paymentProviderSelection(PaymentProviderProperties properties) {
        return () -> properties.provider().name();
    }
}
