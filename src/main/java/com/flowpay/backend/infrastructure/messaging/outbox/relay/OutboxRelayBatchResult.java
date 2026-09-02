package com.flowpay.backend.infrastructure.messaging.outbox.relay;

public record OutboxRelayBatchResult(
        int selectedCount,
        int publishedCount,
        int failedCount
) {

    public OutboxRelayBatchResult {
        if (selectedCount < 0 || publishedCount < 0 || failedCount < 0) {
            throw new IllegalArgumentException("relay batch counts must not be negative");
        }
        if (publishedCount + failedCount != selectedCount) {
            throw new IllegalArgumentException(
                    "published and failed counts must equal selected count"
            );
        }
    }

    public static OutboxRelayBatchResult empty() {
        return new OutboxRelayBatchResult(0, 0, 0);
    }
}
