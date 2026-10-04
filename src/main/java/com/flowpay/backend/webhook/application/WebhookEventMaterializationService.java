package com.flowpay.backend.webhook.application;

import com.flowpay.backend.webhook.domain.WebhookDelivery;
import com.flowpay.backend.webhook.domain.WebhookEvent;
import com.flowpay.backend.webhook.domain.WebhookResourceType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import static com.flowpay.backend.webhook.application.WebhookMaterializationResult.Outcome.*;

@Service
@RequiredArgsConstructor
public class WebhookEventMaterializationService {
    private final WebhookEventRepository events;
    private final WebhookEndpointRepository endpoints;
    private final WebhookDeliveryRepository deliveries;
    private final WebhookEventPublicIdGenerator eventIds;
    private final WebhookDeliveryPublicIdGenerator deliveryIds;
    private final WebhookPublicPayloadCodec payloads;
    private final Clock clock;

    @Transactional
    public WebhookMaterializationResult materialize(MaterializeWebhookEventCommand command) {
        var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        var occurredAt = command.occurredAt().truncatedTo(ChronoUnit.MICROS);
        String publicId = eventIds.nextId();
        var inserted = events.tryInsert(WebhookEvent.create(publicId, command.sourceEventId(),
                command.merchantId(), command.eventType(), WebhookResourceType.forEventType(command.eventType()),
                command.resourceId(), payloads.encode(publicId, command), occurredAt, now));
        if (inserted.isEmpty()) {
            var existing = events.findBySourceEventId(command.sourceEventId()).orElseThrow();
            // The public body also compares the original source timestamp at its full precision.
            if (existing.merchantId() != command.merchantId() || existing.eventType() != command.eventType()
                    || !existing.resourceId().equals(command.resourceId()) || !existing.occurredAt().equals(occurredAt)
                    || !payloads.equivalent(existing.payload(), existing.publicId(), command)) {
                throw new ContradictoryWebhookSourceEventException();
            }
            return new WebhookMaterializationResult(ALREADY_MATERIALIZED, existing.publicId(), 0);
        }
        var event = inserted.orElseThrow();
        var matching = endpoints.findActiveSubscribedIdsForShare(command.merchantId(), command.eventType());
        for (long endpointId : matching) {
            deliveries.save(WebhookDelivery.create(deliveryIds.nextId(), event.internalId(), endpointId, now));
        }
        return new WebhookMaterializationResult(MATERIALIZED, event.publicId(), matching.size());
    }
}
