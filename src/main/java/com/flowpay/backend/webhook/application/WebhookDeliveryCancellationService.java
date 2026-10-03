package com.flowpay.backend.webhook.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;

@Service
@RequiredArgsConstructor
public class WebhookDeliveryCancellationService {
    private final WebhookDeliveryRepository deliveries;
    private final WebhookDeliveryWorkerProperties properties;
    private final Clock clock;

    /** Join endpoint disable's transaction and exclusive endpoint lock; never partially cancel. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void cancelScheduled(long endpointId) {
        while (true) {
            var scheduled = deliveries.findScheduledByEndpointForUpdate(endpointId, properties.batchSize());
            if (scheduled.isEmpty()) return;
            for (var delivery : scheduled) {
                var now = clock.instant();
                if (now.isBefore(delivery.updatedAt())) now = delivery.updatedAt();
                delivery.stopForDisabledEndpoint(now);
                deliveries.save(delivery);
            }
        }
    }
}
