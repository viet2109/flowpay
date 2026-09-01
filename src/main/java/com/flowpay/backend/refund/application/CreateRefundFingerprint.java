package com.flowpay.backend.refund.application;

import com.flowpay.backend.idempotency.application.RequestFingerprintCanonicalizer;
import com.flowpay.backend.idempotency.application.RequestFingerprintInput;
import com.flowpay.backend.refund.domain.RefundReason;

import java.util.Objects;

public record CreateRefundFingerprint(
        int version,
        String paymentPublicId,
        long amountMinor,
        RefundReason reason
) implements RequestFingerprintInput {

    public static final int CURRENT_VERSION = 1;

    public CreateRefundFingerprint {
        if (version != CURRENT_VERSION) {
            throw new IllegalArgumentException(
                    "create refund fingerprint version must be " + CURRENT_VERSION
            );
        }
        Objects.requireNonNull(paymentPublicId, "paymentPublicId must not be null");
        paymentPublicId = paymentPublicId.trim();
        if (paymentPublicId.isEmpty()) {
            throw new IllegalArgumentException("paymentPublicId must not be blank");
        }
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("amountMinor must be positive");
        }
        reason = Objects.requireNonNull(reason, "reason must not be null");
    }

    public static CreateRefundFingerprint version1(
            String paymentPublicId,
            long amountMinor,
            RefundReason reason
    ) {
        return new CreateRefundFingerprint(
                CURRENT_VERSION,
                paymentPublicId,
                amountMinor,
                reason
        );
    }

    @Override
    public void appendTo(RequestFingerprintCanonicalizer canonicalizer) {
        canonicalizer.appendNumber("version", version);
        canonicalizer.appendText("paymentPublicId", paymentPublicId);
        canonicalizer.appendNumber("amountMinor", amountMinor);
        canonicalizer.appendText("reason", reason.value());
    }
}
