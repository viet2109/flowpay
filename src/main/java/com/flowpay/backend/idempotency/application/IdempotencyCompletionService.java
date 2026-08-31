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
public class IdempotencyCompletionService {

    private final IdempotencyRepository repository;
    private final Clock clock;
    private final IdempotencyProperties properties;

    @Transactional
    public void complete(IdempotencyCompletionCommand command) {
        IdempotencyRecord record = repository.findByInternalId(command.executionId())
                .orElseThrow(() -> new IllegalStateException(
                        "Idempotency execution could not be loaded"
                ));
        Instant completedAt = clock.instant();
        record.complete(
                command.resourceType(),
                command.resourcePublicId(),
                command.httpStatus(),
                command.responsePayload(),
                completedAt,
                completedAt.plus(properties.retention())
        );
        repository.save(record);
    }
}
