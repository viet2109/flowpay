package com.flowpay.backend.idempotency.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Objects;

@ConfigurationProperties(prefix = "flowpay.idempotency")
public record IdempotencyProperties(
        Duration retention,
        Cleanup cleanup
) {

    public static final Duration DEFAULT_RETENTION = Duration.ofHours(24);

    public IdempotencyProperties {
        retention = requirePositive(retention, "retention");
        Objects.requireNonNull(cleanup, "cleanup must not be null");
    }

    public record Cleanup(
            boolean enabled,
            int batchSize,
            int maxBatchesPerRun,
            Duration initialDelay,
            Duration fixedDelay
    ) {

        public Cleanup {
            if (batchSize <= 0) {
                throw new IllegalArgumentException("cleanup.batchSize must be positive");
            }
            if (maxBatchesPerRun <= 0) {
                throw new IllegalArgumentException(
                        "cleanup.maxBatchesPerRun must be positive"
                );
            }
            Objects.requireNonNull(initialDelay, "cleanup.initialDelay must not be null");
            if (initialDelay.isNegative()) {
                throw new IllegalArgumentException(
                        "cleanup.initialDelay must not be negative"
                );
            }
            fixedDelay = requirePositive(fixedDelay, "cleanup.fixedDelay");
        }
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
