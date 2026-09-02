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
        String rawApiKey = "fp_live_sensitive-api-key";
        String rawIdempotencyKey = "checkout-sensitive-idempotency-key";
        String rabbitCredential = "amqp://rabbit-user:rabbit-password@broker/vhost";
        String databaseCredential = "jdbc:postgresql://db/flowpay?user=db-user&password=db-password";
        RuntimeException unsafe = new IllegalStateException(
                rabbitCredential
                        + "\n"
                        + databaseCredential
                        + "\nAuthorization: Bearer "
                        + rawApiKey
                        + "\nIdempotency-Key: "
                        + rawIdempotencyKey
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
                        "jdbc:postgresql",
                        rawApiKey,
                        rawIdempotencyKey,
                        "rabbit-password",
                        "db-password",
                        "\n"
                )
                .hasSizeLessThanOrEqualTo(OutboxRelayFailureSummary.MAX_LENGTH);
    }
}
