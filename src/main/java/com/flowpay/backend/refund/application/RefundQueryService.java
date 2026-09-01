package com.flowpay.backend.refund.application;

import com.flowpay.backend.common.error.ApiException;
import com.flowpay.backend.common.error.ErrorCode;
import com.flowpay.backend.common.security.MerchantApiPrincipal;
import com.flowpay.backend.merchant.application.ActiveMerchantSnapshot;
import com.flowpay.backend.merchant.application.MerchantAccessApi;
import com.flowpay.backend.payment.application.OwnedPaymentSnapshot;
import com.flowpay.backend.payment.application.PaymentRefundApi;
import com.flowpay.backend.refund.domain.Refund;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RefundQueryService {

    public static final int DEFAULT_PAGE = 0;
    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    private final MerchantAccessApi merchantAccessApi;
    private final PaymentRefundApi paymentRefundApi;
    private final RefundRepository refundRepository;

    @Transactional(readOnly = true)
    public RefundView get(MerchantApiPrincipal principal, String refundPublicId) {
        ActiveMerchantSnapshot merchant = requireMerchant(principal);
        Refund refund = refundRepository.findByPublicIdAndMerchantId(
                refundPublicId,
                merchant.internalId()
        ).orElseThrow(RefundQueryService::refundNotFound);
        OwnedPaymentSnapshot payment = paymentRefundApi.requireOwnedPaymentByInternalId(
                merchant.internalId(),
                refund.paymentIntentId()
        );
        return toView(refund, payment.paymentPublicId());
    }

    @Transactional(readOnly = true)
    public RefundViewPage list(ListRefundsQuery query) {
        validatePage(query.page(), query.size());
        ActiveMerchantSnapshot merchant = requireMerchant(query.merchantContext());
        OwnedPaymentSnapshot payment = paymentRefundApi.requireOwnedPayment(
                merchant.internalId(),
                query.paymentPublicId()
        );
        RefundPage result = refundRepository.findByPaymentIntentIdAndMerchantId(
                payment.paymentInternalId(),
                merchant.internalId(),
                query.page(),
                query.size()
        );
        return new RefundViewPage(
                result.content().stream()
                        .map(refund -> toView(refund, payment.paymentPublicId()))
                        .toList(),
                result.page(),
                result.size(),
                result.totalElements(),
                result.totalPages(),
                result.hasNext(),
                result.hasPrevious()
        );
    }

    private ActiveMerchantSnapshot requireMerchant(MerchantApiPrincipal principal) {
        return merchantAccessApi.requireActiveMerchant(principal.merchantPublicId());
    }

    private static void validatePage(int page, int size) {
        if (page < 0) {
            throw invalidQuery("page must not be negative");
        }
        if (size <= 0 || size > MAX_SIZE) {
            throw invalidQuery("size must be between 1 and " + MAX_SIZE);
        }
    }

    private static RefundView toView(Refund refund, String paymentPublicId) {
        return new RefundView(
                refund.publicId(),
                paymentPublicId,
                refund.amount().amountMinor(),
                refund.amount().currency().getCurrencyCode(),
                refund.status(),
                refund.reason().value(),
                refund.provider(),
                refund.providerRefundId(),
                refund.failureCode(),
                refund.failureMessage(),
                refund.createdAt(),
                refund.updatedAt(),
                refund.completedAt()
        );
    }

    private static ApiException refundNotFound() {
        return new ApiException(
                HttpStatus.NOT_FOUND,
                ErrorCode.REFUND_NOT_FOUND,
                "The refund was not found."
        );
    }

    private static ApiException invalidQuery(String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR, detail);
    }
}
