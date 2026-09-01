package com.flowpay.backend.refund.api;

import com.flowpay.backend.common.security.MerchantApiPrincipal;
import com.flowpay.backend.idempotency.domain.IdempotencyKey;
import com.flowpay.backend.refund.application.IdempotentRefundCommand;
import com.flowpay.backend.refund.application.ListRefundsQuery;
import com.flowpay.backend.refund.application.RefundResponseSnapshot;
import com.flowpay.backend.refund.application.RefundView;
import com.flowpay.backend.refund.domain.RefundReason;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

import java.util.List;

@Mapper(
        componentModel = MappingConstants.ComponentModel.SPRING,
        unmappedTargetPolicy = ReportingPolicy.ERROR
)
public interface RefundApiMapper {

    @Mapping(target = "merchantContext", source = "merchantContext")
    @Mapping(target = "idempotencyKey", source = "idempotencyKey")
    @Mapping(target = "paymentPublicId", source = "paymentPublicId")
    @Mapping(target = "amountMinor", source = "request.amount")
    @Mapping(target = "reason", source = "reason")
    IdempotentRefundCommand toCommand(
            MerchantApiPrincipal merchantContext,
            IdempotencyKey idempotencyKey,
            String paymentPublicId,
            CreateRefundRequest request,
            RefundReason reason
    );

    RefundResponse toResponse(RefundResponseSnapshot snapshot);

    @Mapping(target = "merchantContext", source = "merchantContext")
    @Mapping(target = "paymentPublicId", source = "paymentPublicId")
    @Mapping(target = "page", source = "page")
    @Mapping(target = "size", source = "size")
    ListRefundsQuery toQuery(
            MerchantApiPrincipal merchantContext,
            String paymentPublicId,
            int page,
            int size
    );

    @Mapping(target = "id", source = "publicId")
    @Mapping(target = "paymentId", source = "paymentPublicId")
    @Mapping(target = "amount", source = "amountMinor")
    RefundResponse toResponse(RefundView view);

    List<RefundResponse> toResponses(List<RefundView> views);
}
