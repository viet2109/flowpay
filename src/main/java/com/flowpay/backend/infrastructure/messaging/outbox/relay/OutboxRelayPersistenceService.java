package com.flowpay.backend.infrastructure.messaging.outbox.relay;

import com.flowpay.backend.infrastructure.messaging.outbox.OutboxRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class OutboxRelayPersistenceService {

    private final OutboxRepository outboxRepository;

    @Transactional(readOnly = true)
    public List<OutboxRelaySnapshot> findDue(Instant dueAt, int batchSize) {
        return outboxRepository.findDueUnpublished(dueAt, batchSize).stream()
                .map(OutboxRelaySnapshot::from)
                .toList();
    }

    @Transactional
    public boolean markPublished(String eventId, Instant publishedAt) {
        return outboxRepository.markPublished(eventId, publishedAt);
    }

    @Transactional
    public boolean recordFailure(
            String eventId,
            Instant nextAvailableAt,
            String failureSummary
    ) {
        return outboxRepository.recordFailure(
                eventId,
                nextAvailableAt,
                failureSummary
        );
    }
}
