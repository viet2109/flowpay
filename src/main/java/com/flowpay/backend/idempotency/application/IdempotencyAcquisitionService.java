package com.flowpay.backend.idempotency.application;

import com.flowpay.backend.idempotency.domain.IdempotencyRecord;
import com.flowpay.backend.idempotency.domain.IdempotencyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

@Service
@RequiredArgsConstructor
public class IdempotencyAcquisitionService {

    static final Duration DEFAULT_RETENTION = Duration.ofHours(24);

    private final IdempotencyRepository repository;
    private final Clock clock;

    @Transactional
    public IdempotencyAcquisitionResult acquire(IdempotencyAcquisitionCommand command) {
        Instant createdAt = clock.instant();
        IdempotencyRecord candidate = IdempotencyRecord.start(
                command.merchantId(),
                command.operation(),
                command.idempotencyKey(),
                command.requestHash(),
                createdAt,
                createdAt.plus(DEFAULT_RETENTION)
        );

        return repository.tryInsert(candidate)
                .map(inserted -> new IdempotencyAcquisitionResult(
                        IdempotencyAcquisitionDecision.NEW,
                        inserted
                ))
                .orElseGet(() -> decideExisting(command));
    }

    private IdempotencyAcquisitionResult decideExisting(
            IdempotencyAcquisitionCommand command
    ) {
        IdempotencyRecord existing = repository.findByScope(
                command.merchantId(),
                command.operation(),
                command.idempotencyKey()
        ).orElseThrow(() -> new IllegalStateException(
                "Conflicting idempotency record could not be loaded"
        ));

        if (!existing.requestHash().equals(command.requestHash())) {
            return new IdempotencyAcquisitionResult(
                    IdempotencyAcquisitionDecision.KEY_REUSED,
                    existing
            );
        }
        if (existing.isCompleted()) {
            return new IdempotencyAcquisitionResult(
                    IdempotencyAcquisitionDecision.REPLAY,
                    existing
            );
        }
        return new IdempotencyAcquisitionResult(
                IdempotencyAcquisitionDecision.IN_PROGRESS,
                existing
        );
    }
}
