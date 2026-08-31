package com.flowpay.backend.idempotency.application;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotencyPropertiesTest {

    @Test
    void defaultRetentionShouldBeTwentyFourHours() {
        assertThat(IdempotencyProperties.DEFAULT_RETENTION).isEqualTo(Duration.ofHours(24));
    }

    @Test
    void shouldRejectUnboundedOrInvalidCleanupConfiguration() {
        assertThatThrownBy(() -> cleanup(0, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("cleanup.batchSize must be positive");
        assertThatThrownBy(() -> cleanup(10, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("cleanup.maxBatchesPerRun must be positive");
    }

    private IdempotencyProperties.Cleanup cleanup(int batchSize, int maxBatches) {
        return new IdempotencyProperties.Cleanup(
                true,
                batchSize,
                maxBatches,
                Duration.ZERO,
                Duration.ofMinutes(15)
        );
    }
}
