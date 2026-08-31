package com.flowpay.backend.payment.infrastructure;

import com.flowpay.backend.payment.application.ConfirmPaymentResponseSnapshot;
import com.flowpay.backend.payment.application.ConfirmPaymentResponseSnapshotCodec;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
final class JacksonConfirmPaymentResponseSnapshotCodec
        implements ConfirmPaymentResponseSnapshotCodec {

    private final ObjectMapper objectMapper;

    @Override
    public String encode(ConfirmPaymentResponseSnapshot snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JacksonException exception) {
            throw new IllegalStateException(
                    "Confirm payment response snapshot could not be encoded",
                    exception
            );
        }
    }

    @Override
    public ConfirmPaymentResponseSnapshot decode(String payload) {
        try {
            return objectMapper.readValue(payload, ConfirmPaymentResponseSnapshot.class);
        } catch (JacksonException exception) {
            throw new IllegalStateException(
                    "Stored confirm payment response snapshot is invalid",
                    exception
            );
        }
    }
}
