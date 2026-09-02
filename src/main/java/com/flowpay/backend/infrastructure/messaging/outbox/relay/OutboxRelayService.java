package com.flowpay.backend.infrastructure.messaging.outbox.relay;

import com.flowpay.backend.infrastructure.messaging.FlowPayMessagingProperties;
import com.flowpay.backend.infrastructure.messaging.IntegrationEventPublicationResult;
import com.flowpay.backend.infrastructure.messaging.IntegrationEventTransportPublisher;
import com.flowpay.backend.infrastructure.messaging.outbox.IntegrationEventEnvelope;
import com.flowpay.backend.infrastructure.messaging.outbox.IntegrationEventEnvelopeMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxRelayService {

    private final OutboxRelayPersistenceService persistenceService;
    private final IntegrationEventEnvelopeMapper envelopeMapper;
    private final IntegrationEventTransportPublisher transportPublisher;
    private final OutboxRelayBackoffPolicy backoffPolicy;
    private final OutboxRelayFailureSummary failureSummary;
    private final FlowPayMessagingProperties properties;
    private final Clock clock;

    public OutboxRelayBatchResult relayDueEvents() {
        int batchSize = properties.outbox().relay().batchSize();
        List<OutboxRelaySnapshot> snapshots = persistenceService.findDue(
                clock.instant(),
                batchSize
        );
        if (snapshots.isEmpty()) {
            return OutboxRelayBatchResult.empty();
        }

        int publishedCount = 0;
        for (OutboxRelaySnapshot snapshot : snapshots) {
            PublicationAttempt attempt = publish(snapshot);
            if (attempt.confirmed()) {
                persistPublished(snapshot.eventId());
                publishedCount++;
            } else {
                persistFailure(snapshot, attempt.failureSummary());
            }
        }
        return new OutboxRelayBatchResult(
                snapshots.size(),
                publishedCount,
                snapshots.size() - publishedCount
        );
    }

    private PublicationAttempt publish(OutboxRelaySnapshot snapshot) {
        try {
            IntegrationEventEnvelope envelope = envelopeMapper.from(snapshot);
            IntegrationEventPublicationResult result = transportPublisher.publish(envelope);
            if (result.confirmed()) {
                return PublicationAttempt.success();
            }
            return PublicationAttempt.failure(
                    failureSummary.forTransportStatus(result.status())
            );
        } catch (RuntimeException exception) {
            return PublicationAttempt.failure(
                    failureSummary.forUnexpectedFailure(exception)
            );
        }
    }

    private void persistPublished(String eventId) {
        try {
            persistenceService.markPublished(eventId, clock.instant());
        } catch (RuntimeException exception) {
            log.warn(
                    "Outbox relay could not persist confirmed publication eventId={}",
                    eventId
            );
        }
    }

    private void persistFailure(
            OutboxRelaySnapshot snapshot,
            String safeFailureSummary
    ) {
        Instant failedAt = clock.instant();
        Duration delay = backoffPolicy.delayAfterFailure(snapshot.retryCount());
        try {
            persistenceService.recordFailure(
                    snapshot.eventId(),
                    failedAt.plus(delay),
                    safeFailureSummary
            );
        } catch (RuntimeException exception) {
            log.warn(
                    "Outbox relay could not persist publication failure eventId={}",
                    snapshot.eventId()
            );
        }
    }

    private record PublicationAttempt(
            boolean confirmed,
            String failureSummary
    ) {

        private static PublicationAttempt success() {
            return new PublicationAttempt(true, null);
        }

        private static PublicationAttempt failure(String failureSummary) {
            return new PublicationAttempt(false, failureSummary);
        }
    }
}
