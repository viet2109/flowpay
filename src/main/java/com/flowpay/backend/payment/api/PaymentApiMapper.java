package com.flowpay.backend.payment.api;

import com.flowpay.backend.common.security.MerchantApiPrincipal;
import com.flowpay.backend.payment.application.PaymentIntentView;
import com.flowpay.backend.payment.application.PaymentTransactionView;
import com.flowpay.backend.payment.application.SearchPaymentIntentsQuery;
import com.flowpay.backend.payment.domain.PaymentStatus;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

import java.time.Instant;
import java.util.List;

@Mapper(
        componentModel = MappingConstants.ComponentModel.SPRING,
        unmappedTargetPolicy = ReportingPolicy.ERROR
)
public interface PaymentApiMapper {

    @Mapping(target = "merchantContext", source = "merchantContext")
    @Mapping(target = "status", source = "status")
    @Mapping(target = "orderId", source = "orderId")
    @Mapping(target = "createdFrom", source = "createdFrom")
    @Mapping(target = "createdTo", source = "createdTo")
    @Mapping(target = "page", source = "page")
    @Mapping(target = "size", source = "size")
    SearchPaymentIntentsQuery toQuery(
            MerchantApiPrincipal merchantContext,
            PaymentStatus status,
            String orderId,
            Instant createdFrom,
            Instant createdTo,
            int page,
            int size
    );

    @Mapping(target = "id", source = "publicId")
    @Mapping(target = "amount", source = "amountMinor")
    @Mapping(target = "refundedAmount", source = "refundedAmountMinor")
    @Mapping(target = "refundableAmount", source = "refundableAmountMinor")
    PaymentIntentResponse toResponse(PaymentIntentView view);

    List<PaymentIntentResponse> toPaymentResponses(List<PaymentIntentView> views);

    @Mapping(target = "id", source = "publicId")
    PaymentTransactionResponse toResponse(PaymentTransactionView view);

    List<PaymentTransactionResponse> toTransactionResponses(List<PaymentTransactionView> views);
}
