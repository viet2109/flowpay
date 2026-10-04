package com.flowpay.backend.webhook.infrastructure.delivery;

import com.flowpay.backend.webhook.application.WebhookDeliveryWorker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "flowpay.webhook.delivery-worker.enabled", havingValue = "true", matchIfMissing = true)
class WebhookDeliveryScheduler {
    private final WebhookDeliveryWorker worker;

    @Scheduled(fixedDelayString = "${flowpay.webhook.delivery-worker.fixed-delay:1s}")
    void deliver() {
        try {
            worker.deliverBatch();
        } catch (RuntimeException exception) {
            log.warn("Webhook delivery batch failed; it will be retried on the next scheduler tick");
        }
    }
}
