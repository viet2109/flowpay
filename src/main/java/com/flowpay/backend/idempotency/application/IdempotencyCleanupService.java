package com.flowpay.backend.idempotency.application;

import com.flowpay.backend.idempotency.domain.IdempotencyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

@Service
@RequiredArgsConstructor
public class IdempotencyCleanupService {

    private final IdempotencyRepository repository;
    private final Clock clock;
    private final IdempotencyProperties properties;

    @Transactional
    public int cleanupExpiredCompleted() {
        Instant expiresBefore = clock.instant();
        int batchSize = properties.cleanup().batchSize();
        int deletedTotal = 0;

        for (int batch = 0; batch < properties.cleanup().maxBatchesPerRun(); batch++) {
            int deleted = repository.deleteExpiredCompletedBefore(
                    expiresBefore,
                    batchSize
            );
            deletedTotal += deleted;
            if (deleted < batchSize) {
                break;
            }
        }

        return deletedTotal;
    }
}
