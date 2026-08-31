package com.flowpay.backend.idempotency.application;

import com.flowpay.backend.idempotency.domain.IdempotencyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class IdempotencyReservationReleaseService {

    private final IdempotencyRepository repository;

    @Transactional
    public void release(IdempotencyReservationReleaseCommand command) {
        boolean released = repository.releaseProcessingReservation(
                command.executionId(),
                command.merchantId(),
                command.operation(),
                command.idempotencyKey(),
                command.requestHash(),
                command.resourceType(),
                command.resourcePublicId()
        );
        if (!released) {
            throw new IllegalStateException(
                    "The PROCESSING idempotency reservation is no longer owned"
            );
        }
    }
}
