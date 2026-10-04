package com.flowpay.backend.webhook.application;

import com.flowpay.backend.common.money.Money;
import com.flowpay.backend.webhook.domain.WebhookEventType;
import com.flowpay.backend.webhook.domain.WebhookResourceType;
import java.time.Instant;
import java.util.Objects;

/** Only normalized source facts; no transport or current Payment/Refund state. */
public record MaterializeWebhookEventCommand(String sourceEventId, long merchantId,
        WebhookEventType eventType, String resourceId, String paymentId, Money amount,
        String failureCode, String failureMessage, Instant occurredAt) {
    public MaterializeWebhookEventCommand {
        sourceEventId = text(sourceEventId);
        if (merchantId <= 0) {
            throw new IllegalArgumentException("merchantId must be positive");
        }
        Objects.requireNonNull(eventType, "eventType must not be null");
        resourceId = publicId(resourceId, WebhookResourceType.forEventType(eventType).publicIdPrefix());
        if (WebhookResourceType.forEventType(eventType) == WebhookResourceType.REFUND) {
            paymentId = publicId(paymentId, "pi_");
        } else if (paymentId != null) {
            throw new IllegalArgumentException("Payment events do not have a separate paymentId");
        }
        if (!Objects.requireNonNull(amount, "amount must not be null").isPositive()) {
            throw new IllegalArgumentException("amount must be positive");
        }
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        if (eventType == WebhookEventType.PAYMENT_FAILED || eventType == WebhookEventType.REFUND_FAILED) {
            failureCode = bounded(failureCode, 64);
            failureMessage = bounded(failureMessage, 255);
        } else if (failureCode != null || failureMessage != null) {
            throw new IllegalArgumentException("Only failed events carry failure metadata");
        }
    }

    public String status() {
        return switch (eventType) {
            case PAYMENT_PROCESSING, REFUND_PROCESSING -> "PROCESSING";
            case PAYMENT_SUCCEEDED, REFUND_SUCCEEDED -> "SUCCEEDED";
            case PAYMENT_FAILED, REFUND_FAILED -> "FAILED";
        };
    }

    private static String publicId(String value, String prefix) {
        String normalized = text(value);
        if (!normalized.startsWith(prefix) || normalized.length() <= prefix.length() || normalized.length() > 64) {
            throw new IllegalArgumentException("Invalid public resource identity");
        }
        return normalized;
    }

    private static String bounded(String value, int limit) {
        String normalized = text(value);
        int characters = Math.min(normalized.codePointCount(0, normalized.length()), limit);
        return normalized.substring(0, normalized.offsetByCodePoints(0, characters));
    }

    private static String text(String value) {
        String normalized = Objects.requireNonNull(value, "Source fact must not be null").trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Source fact must not be blank");
        }
        return normalized;
    }
}
