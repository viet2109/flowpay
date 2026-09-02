package com.flowpay.backend.infrastructure.messaging;

import java.util.Objects;

public record IntegrationEventPublicationResult(
        IntegrationEventPublicationStatus status
) {

    public IntegrationEventPublicationResult {
        status = Objects.requireNonNull(status, "status must not be null");
    }

    public static IntegrationEventPublicationResult of(
            IntegrationEventPublicationStatus status
    ) {
        return new IntegrationEventPublicationResult(status);
    }

    public boolean confirmed() {
        return status == IntegrationEventPublicationStatus.CONFIRMED;
    }
}
