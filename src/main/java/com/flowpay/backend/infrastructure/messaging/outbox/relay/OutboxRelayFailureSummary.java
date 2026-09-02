package com.flowpay.backend.infrastructure.messaging.outbox.relay;

import com.flowpay.backend.infrastructure.messaging.IntegrationEventPublicationStatus;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
public class OutboxRelayFailureSummary {

    public static final int MAX_LENGTH = 256;

    public String forTransportStatus(IntegrationEventPublicationStatus status) {
        IntegrationEventPublicationStatus failureStatus = Objects.requireNonNull(
                status,
                "status must not be null"
        );
        return bounded("RabbitMQ publication failed: " + failureStatus.name());
    }

    public String forUnexpectedFailure(RuntimeException exception) {
        RuntimeException failure = Objects.requireNonNull(
                exception,
                "exception must not be null"
        );
        return bounded(
                "Outbox publication failed unexpectedly: "
                        + failure.getClass().getSimpleName()
        );
    }

    private static String bounded(String value) {
        String sanitized = value
                .replaceAll("[\\p{Cntrl}]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return sanitized.length() <= MAX_LENGTH
                ? sanitized
                : sanitized.substring(0, MAX_LENGTH);
    }
}
