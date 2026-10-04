package com.flowpay.backend.webhook.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.zip.CRC32;

/** Deterministic additive jitter: identical delivery/next-attempt identities survive restarts. */
@Component
@RequiredArgsConstructor
public class ScheduledWebhookDeliveryFailurePolicy implements WebhookDeliveryFailurePolicy {
    private final WebhookDeliveryRetryProperties properties;

    @Override
    public Optional<Instant> nextAttemptAt(String deliveryPublicId, int failedAttemptNo, Instant now) {
        if (failedAttemptNo <= 0) throw new IllegalArgumentException("failed attempt must be positive");
        if (failedAttemptNo > properties.retryDelays().size()) return Optional.empty();
        long baseNanos = properties.retryDelays().get(failedAttemptNo - 1).toNanos();
        var hash = new CRC32();
        hash.update((deliveryPublicId + ":" + (failedAttemptNo + 1)).getBytes(StandardCharsets.UTF_8));
        long jitter = BigDecimal.valueOf(baseNanos).multiply(properties.retryJitterMax())
                .multiply(BigDecimal.valueOf(hash.getValue()))
                .divide(BigDecimal.valueOf(0xffff_ffffL), 0, RoundingMode.DOWN).longValueExact();
        return Optional.of(now.plusNanos(Math.addExact(baseNanos, jitter)));
    }
}
