package com.flowpay.backend.infrastructure.messaging.outbox.relay;

import com.flowpay.backend.infrastructure.messaging.IntegrationEventPublicationStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxRelayFailureSummaryTest {

    private final OutboxRelayFailureSummary failureSummary =
            new OutboxRelayFailureSummary();

    @Test
    void shouldStoreStableTransportStatusWithoutCredentials() {
        String summary = failureSummary.forTransportStatus(
                IntegrationEventPublicationStatus.CONFIRM_TIMEOUT
        );

        assertThat(summary).isEqualTo(
                "RabbitMQ publication failed: CONFIRM_TIMEOUT"
        );
        assertThat(summary).hasSizeLessThanOrEqualTo(
                OutboxRelayFailureSummary.MAX_LENGTH
        );
    }

    @Test
    void shouldIgnoreUnsafeExceptionMessageAndStackTrace() {
        RuntimeException unsafe = new IllegalStateException(
                "amqp://admin:super-secret@broker/vhost\nAuthorization: Bearer token"
                        + "x".repeat(1_000)
        );

        String summary = failureSummary.forUnexpectedFailure(unsafe);

        assertThat(summary)
                .isEqualTo(
                        "Outbox publication failed unexpectedly: IllegalStateException"
                )
                .doesNotContain(
                        "super-secret",
                        "Authorization",
                        "Bearer",
                        "amqp://",
                        "\n"
                )
                .hasSizeLessThanOrEqualTo(OutboxRelayFailureSummary.MAX_LENGTH);
    }
}
