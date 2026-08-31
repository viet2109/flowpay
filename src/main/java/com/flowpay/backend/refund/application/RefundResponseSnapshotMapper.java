package com.flowpay.backend.refund.application;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

@Mapper(
        componentModel = MappingConstants.ComponentModel.SPRING,
        unmappedTargetPolicy = ReportingPolicy.ERROR
)
public interface RefundResponseSnapshotMapper {

    @Mapping(target = "id", source = "refundPublicId")
    @Mapping(target = "paymentId", source = "paymentPublicId")
    @Mapping(target = "amount", source = "amount.amountMinor")
    @Mapping(target = "currency", source = "amount.currency.currencyCode")
    @Mapping(target = "reason", source = "reason.value")
    RefundResponseSnapshot toSnapshot(FinalizedRefund finalized);
}
