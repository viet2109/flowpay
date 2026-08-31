package com.flowpay.backend.payment.application;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

@Mapper(
        componentModel = MappingConstants.ComponentModel.SPRING,
        unmappedTargetPolicy = ReportingPolicy.ERROR
)
public interface CreatePaymentResponseSnapshotMapper {

    @Mapping(target = "id", source = "publicId")
    @Mapping(target = "amount", source = "amountMinor")
    @Mapping(target = "refundedAmount", source = "refundedAmountMinor")
    @Mapping(target = "refundableAmount", source = "refundableAmountMinor")
    CreatePaymentResponseSnapshot toSnapshot(CreatePaymentIntentResult result);
}
