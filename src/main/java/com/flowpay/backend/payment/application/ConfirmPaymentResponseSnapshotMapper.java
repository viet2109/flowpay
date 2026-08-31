package com.flowpay.backend.payment.application;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

@Mapper(
        componentModel = MappingConstants.ComponentModel.SPRING,
        unmappedTargetPolicy = ReportingPolicy.ERROR
)
public interface ConfirmPaymentResponseSnapshotMapper {

    @Mapping(target = "paymentId", source = "paymentPublicId")
    @Mapping(target = "transactionId", source = "transactionPublicId")
    ConfirmPaymentResponseSnapshot toSnapshot(FinalizedPaymentConfirmation confirmation);
}
