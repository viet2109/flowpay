package com.flowpay.backend.webhook.infrastructure;

import com.flowpay.backend.webhook.application.MaterializeWebhookEventCommand;
import com.flowpay.backend.webhook.application.WebhookPublicPayloadCodec;
import com.flowpay.backend.webhook.domain.WebhookResourceType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class JacksonWebhookPublicPayloadCodec implements WebhookPublicPayloadCodec {
    private final ObjectMapper mapper;
    private final WebhookPublicPayloadMapper payloadMapper;

    @Override
    public String encode(String eventPublicId, MaterializeWebhookEventCommand command) {
        boolean refund = WebhookResourceType.forEventType(command.eventType()) == WebhookResourceType.REFUND;
        Object data = refund
                ? new WebhookPublicPayload.RefundData(payloadMapper.refund(command, command.status()))
                : new WebhookPublicPayload.PaymentData(payloadMapper.payment(command, command.status()));
        return mapper.writeValueAsString(new WebhookPublicPayload(eventPublicId, command.eventType().value(),
                command.occurredAt().toString(), data));
    }

    @Override
    public boolean equivalent(String existingPayload, String eventPublicId, MaterializeWebhookEventCommand command) {
        return mapper.readTree(existingPayload).equals(mapper.readTree(encode(eventPublicId, command)));
    }
}
