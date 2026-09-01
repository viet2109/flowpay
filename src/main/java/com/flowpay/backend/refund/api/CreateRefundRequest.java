package com.flowpay.backend.refund.api;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.flowpay.backend.refund.domain.RefundReason;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record CreateRefundRequest(
        @Positive(message = "Amount must be greater than zero.")
        long amount,

        @Size(
                max = RefundReason.MAX_LENGTH,
                message = "Reason must not exceed 255 characters."
        )
        String reason
) {

    public CreateRefundRequest {
        if (reason != null) {
            reason = reason.trim();
            if (reason.isEmpty()) {
                reason = null;
            }
        }
    }

    @JsonAnySetter
    public void rejectUnknownProperty(String name, Object value) {
        throw new IllegalArgumentException("Unknown Refund request property");
    }
}
