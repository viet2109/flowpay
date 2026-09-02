package com.flowpay.backend.infrastructure.messaging.outbox.relay;

import com.flowpay.backend.infrastructure.messaging.FlowPayMessagingProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class OutboxRelayBackoffPolicy {

    private final Duration initialBackoff;
    private final Duration maxBackoff;

    public OutboxRelayBackoffPolicy(FlowPayMessagingProperties properties) {
        FlowPayMessagingProperties.Relay relay = properties.outbox().relay();
        initialBackoff = relay.initialBackoff();
        maxBackoff = relay.maxBackoff();
    }

    public Duration delayAfterFailure(int previousFailureCount) {
        if (previousFailureCount < 0) {
            throw new IllegalArgumentException(
                    "previousFailureCount must not be negative"
            );
        }
        Duration delay = initialBackoff;
        for (int index = 0; index < previousFailureCount; index++) {
            if (delay.compareTo(maxBackoff) >= 0) {
                return maxBackoff;
            }
            try {
                delay = delay.multipliedBy(2);
            } catch (ArithmeticException exception) {
                return maxBackoff;
            }
            if (delay.compareTo(maxBackoff) >= 0) {
                return maxBackoff;
            }
        }
        return delay;
    }
}
