package com.flowpay.backend.infrastructure.messaging.outbox.relay;

import com.flowpay.backend.infrastructure.messaging.outbox.OutboxRepository;
import com.flowpay.backend.infrastructure.messaging.outbox.metrics.OutboxMetrics;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class OutboxRelayPersistenceService {

    private final OutboxRepository outboxRepository;
    private final OutboxMetrics metrics;

    @Transactional(readOnly = true)
    public List<OutboxRelaySnapshot> findDue(Instant dueAt, int batchSize) {
        return outboxRepository.findDueUnpublished(dueAt, batchSize).stream()
                .map(OutboxRelaySnapshot::from)
                .toList();
    }

    @Transactional
    public boolean markPublished(String eventId, Instant publishedAt) {
        boolean changed = outboxRepository.markPublished(eventId, publishedAt);
        if (changed) metrics.published();
        return changed;
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
