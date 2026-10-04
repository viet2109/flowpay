package com.flowpay.backend.webhook.infrastructure;

import com.fasterxml.jackson.annotation.JsonInclude;

record WebhookPublicPayload(String id, String type, String createdAt, Object data) {
    record PaymentData(Payment payment) { }
    record RefundData(Refund refund) { }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    record Payment(String id, long amount, String currency, String status,
                   String failureCode, String failureMessage) { }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    record Refund(String id, String paymentId, long amount, String currency, String status,
                  String failureCode, String failureMessage) { }
}
