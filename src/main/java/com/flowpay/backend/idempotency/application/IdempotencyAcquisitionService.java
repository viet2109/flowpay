package com.flowpay.backend.idempotency.application;

import com.flowpay.backend.idempotency.domain.IdempotencyRecord;
import com.flowpay.backend.idempotency.domain.IdempotencyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

@Service
@RequiredArgsConstructor
public class IdempotencyAcquisitionService {

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
                createdAt.plus(IdempotencyRetention.DEFAULT)
        );

        return repository.tryInsert(candidate)
                .map(inserted -> IdempotencyAcquisitionResult.newExecution(
                        requireInternalId(inserted)
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
            return IdempotencyAcquisitionResult.keyReused();
        }
        if (existing.isCompleted()) {
            return IdempotencyAcquisitionResult.replay(new IdempotencyStoredResponse(
                    existing.resourceType(),
                    existing.resourcePublicId(),
                    existing.httpStatus(),
                    existing.responsePayload()
            ));
        }
        return IdempotencyAcquisitionResult.inProgress();
    }

    private static long requireInternalId(IdempotencyRecord record) {
        if (record.internalId() == null) {
            throw new IllegalStateException("Inserted idempotency record has no identifier");
        }
        return record.internalId();
    }
}
