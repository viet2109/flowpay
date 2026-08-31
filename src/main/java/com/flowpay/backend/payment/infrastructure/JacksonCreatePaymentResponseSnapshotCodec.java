package com.flowpay.backend.payment.infrastructure;

import com.flowpay.backend.payment.application.CreatePaymentResponseSnapshot;
import com.flowpay.backend.payment.application.CreatePaymentResponseSnapshotCodec;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
final class JacksonCreatePaymentResponseSnapshotCodec
        implements CreatePaymentResponseSnapshotCodec {

    private final ObjectMapper objectMapper;

    @Override
    public String encode(CreatePaymentResponseSnapshot snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JacksonException exception) {
            throw new IllegalStateException(
                    "Create payment response snapshot could not be encoded",
                    exception
            );
        }
    }

    @Override
    public CreatePaymentResponseSnapshot decode(String payload) {
        try {
            return objectMapper.readValue(payload, CreatePaymentResponseSnapshot.class);
        } catch (JacksonException exception) {
            throw new IllegalStateException(
                    "Stored create payment response snapshot is invalid",
                    exception
            );
        }
    }
}
