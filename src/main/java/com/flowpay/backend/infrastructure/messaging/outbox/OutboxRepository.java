package com.flowpay.backend.infrastructure.messaging.outbox;

import java.time.Instant;
import java.util.List;

public interface OutboxRepository {

    OutboxEvent save(OutboxEvent event);

    List<OutboxEvent> findDueUnpublished(Instant availableAt, int limit);

    boolean markPublished(String eventId, Instant publishedAt);

    boolean recordFailure(String eventId, Instant nextAvailableAt, String lastError);
}
