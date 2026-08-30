package com.flowpay.backend.payment.infrastructure.provider;

import com.flowpay.backend.payment.domain.ProviderOutcome;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Objects;

@ConfigurationProperties(prefix = "flowpay.payment")
public record PaymentProviderProperties(
        Provider provider,
        Simulator simulator
) {

    public PaymentProviderProperties {
        Objects.requireNonNull(provider, "provider must not be null");
        Objects.requireNonNull(simulator, "simulator must not be null");
    }

    public enum Provider {
        SIMULATOR
    }

    public record Simulator(ProviderOutcome defaultOutcome) {

        public Simulator {
            Objects.requireNonNull(defaultOutcome, "defaultOutcome must not be null");
        }
    }
}
