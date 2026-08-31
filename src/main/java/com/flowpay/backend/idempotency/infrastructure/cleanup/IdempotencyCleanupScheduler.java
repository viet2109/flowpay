package com.flowpay.backend.idempotency.infrastructure.cleanup;

import com.flowpay.backend.idempotency.application.IdempotencyCleanupService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "flowpay.idempotency.cleanup",
        name = "enabled",
        havingValue = "true"
)
class IdempotencyCleanupScheduler {

    private final IdempotencyCleanupService cleanupService;

    @Scheduled(
            initialDelayString = "${flowpay.idempotency.cleanup.initial-delay}",
            fixedDelayString = "${flowpay.idempotency.cleanup.fixed-delay}"
    )
    void cleanup() {
        cleanupService.cleanupExpiredCompleted();
    }
}
