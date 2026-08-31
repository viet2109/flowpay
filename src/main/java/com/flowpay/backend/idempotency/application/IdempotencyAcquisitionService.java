package com.flowpay.backend.idempotency.application;

import com.flowpay.backend.idempotency.domain.IdempotencyRecord;
import com.flowpay.backend.idempotency.domain.IdempotencyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class IdempotencyAcquisitionService {

    private final IdempotencyRepository repository;
    private final Clock clock;
    private final IdempotencyProperties properties;

    @Transactional
    public IdempotencyAcquisitionResult acquire(IdempotencyAcquisitionCommand command) {
        Instant createdAt = clock.instant();
        IdempotencyRecord candidate = newCandidate(command, createdAt);

        return repository.tryInsert(candidate)
                .map(inserted -> IdempotencyAcquisitionResult.newExecution(
                        requireInternalId(inserted)
                ))
                .orElseGet(() -> findExistingDecision(command).orElseThrow(() ->
                        new IllegalStateException(
                                "Conflicting idempotency record could not be loaded"
                        )
                ));
    }

    @Transactional(readOnly = true)
    public Optional<IdempotencyAcquisitionResult> findExisting(
            IdempotencyAcquisitionCommand command
    ) {
        return findExistingDecision(command);
    }

    private IdempotencyRecord newCandidate(
            IdempotencyAcquisitionCommand command,
            Instant createdAt
    ) {
        Instant expiresAt = createdAt.plus(properties.retention());
        if (command.hasResource()) {
            return IdempotencyRecord.startForResource(
                    command.merchantId(),
                    command.operation(),
                    command.idempotencyKey(),
                    command.requestHash(),
                    command.resourceType(),
                    command.resourcePublicId(),
                    createdAt,
                    expiresAt
            );
        }
        return IdempotencyRecord.start(
                command.merchantId(),
                command.operation(),
                command.idempotencyKey(),
                command.requestHash(),
                createdAt,
                expiresAt
        );
    }

    private Optional<IdempotencyAcquisitionResult> findExistingDecision(
            IdempotencyAcquisitionCommand command
    ) {
        return repository.findByScope(
                command.merchantId(),
                command.operation(),
                command.idempotencyKey()
        ).map(existing -> decideExisting(command, existing));
    }

    private static IdempotencyAcquisitionResult decideExisting(
            IdempotencyAcquisitionCommand command,
            IdempotencyRecord existing
    ) {
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
