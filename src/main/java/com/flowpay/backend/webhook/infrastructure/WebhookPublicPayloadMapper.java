package com.flowpay.backend.webhook.infrastructure;

import com.flowpay.backend.webhook.application.MaterializeWebhookEventCommand;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

/** Mechanical scalar DTO mapping; validation and event/status rules live in the command. */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface WebhookPublicPayloadMapper {
    @Mapping(target = "id", source = "command.resourceId")
    @Mapping(target = "amount", source = "command.amount.amountMinor")
    @Mapping(target = "currency", source = "command.amount.currency.currencyCode")
    @Mapping(target = "failureCode", source = "command.failureCode")
    @Mapping(target = "failureMessage", source = "command.failureMessage")
    WebhookPublicPayload.Payment payment(MaterializeWebhookEventCommand command, String status);

    @Mapping(target = "id", source = "command.resourceId")
    @Mapping(target = "paymentId", source = "command.paymentId")
    @Mapping(target = "amount", source = "command.amount.amountMinor")
    @Mapping(target = "currency", source = "command.amount.currency.currencyCode")
    @Mapping(target = "failureCode", source = "command.failureCode")
    @Mapping(target = "failureMessage", source = "command.failureMessage")
    WebhookPublicPayload.Refund refund(MaterializeWebhookEventCommand command, String status);
}
