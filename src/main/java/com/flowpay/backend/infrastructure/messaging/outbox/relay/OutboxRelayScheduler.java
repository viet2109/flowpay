package com.flowpay.backend.infrastructure.messaging.outbox.relay;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "flowpay.messaging.outbox.relay",
        name = "enabled",
        havingValue = "true"
)
class OutboxRelayScheduler {

    private final OutboxRelayService relayService;

    @Scheduled(fixedDelayString = "${flowpay.messaging.outbox.relay.fixed-delay}")
    void relay() {
        try {
            relayService.relayDueEvents();
        } catch (RuntimeException exception) {
            log.warn("Outbox relay batch could not be started");
        }
    }
}
