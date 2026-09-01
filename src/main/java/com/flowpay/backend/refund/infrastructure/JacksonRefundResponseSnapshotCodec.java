package com.flowpay.backend.refund.infrastructure;

import com.flowpay.backend.refund.application.RefundResponseSnapshot;
import com.flowpay.backend.refund.application.RefundResponseSnapshotCodec;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
final class JacksonRefundResponseSnapshotCodec implements RefundResponseSnapshotCodec {

    private final ObjectMapper objectMapper;

    @Override
    public String encode(RefundResponseSnapshot snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JacksonException exception) {
            throw new IllegalStateException(
                    "Refund response snapshot could not be encoded",
                    exception
            );
        }
    }

    @Override
    public RefundResponseSnapshot decode(String payload) {
        try {
            return objectMapper.readValue(payload, RefundResponseSnapshot.class);
        } catch (JacksonException exception) {
            throw new IllegalStateException(
                    "Stored Refund response snapshot is invalid",
                    exception
            );
        }
    }
}
