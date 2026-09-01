package com.flowpay.backend.refund.infrastructure.provider;

import com.flowpay.backend.refund.domain.RefundProviderOutcome;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Objects;

@ConfigurationProperties(prefix = "flowpay.refund")
public record RefundProviderProperties(
        Provider provider,
        Simulator simulator
) {

    public RefundProviderProperties {
        Objects.requireNonNull(provider, "provider must not be null");
        Objects.requireNonNull(simulator, "simulator must not be null");
    }

    public enum Provider {
        SIMULATOR
    }

    public record Simulator(RefundProviderOutcome defaultOutcome) {

        public Simulator {
            Objects.requireNonNull(defaultOutcome, "defaultOutcome must not be null");
        }
    }
}
