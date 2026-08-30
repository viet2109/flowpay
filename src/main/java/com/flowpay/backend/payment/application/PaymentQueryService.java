package com.flowpay.backend.payment.application;

import com.flowpay.backend.common.error.ApiException;
import com.flowpay.backend.common.error.ErrorCode;
import com.flowpay.backend.common.security.MerchantApiPrincipal;
import com.flowpay.backend.merchant.application.ActiveMerchantSnapshot;
import com.flowpay.backend.payment.domain.PaymentIntent;
import com.flowpay.backend.payment.domain.PaymentTransaction;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class PaymentQueryService {

    public static final int DEFAULT_PAGE = 0;
    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    private final PaymentMerchantResolver merchantResolver;
    private final PaymentOwnershipService ownershipService;
    private final PaymentIntentRepository paymentIntentRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;

    @Transactional(readOnly = true)
    public PaymentIntentView get(MerchantApiPrincipal principal, String paymentPublicId) {
        return toView(ownershipService.requireOwnedPayment(principal, paymentPublicId));
    }

    @Transactional(readOnly = true)
    public PaymentIntentViewPage search(SearchPaymentIntentsQuery query) {
        validateSearch(query);
        ActiveMerchantSnapshot merchant = merchantResolver.resolve(query.merchantContext());
        PaymentIntentPage result = paymentIntentRepository.search(new PaymentIntentSearchCriteria(
                merchant.internalId(),
                query.status(),
                query.orderId(),
                query.createdFrom(),
                query.createdTo(),
                query.page(),
                query.size()
        ));
        return new PaymentIntentViewPage(
                result.content().stream().map(PaymentQueryService::toView).toList(),
                result.page(),
                result.size(),
                result.totalElements(),
                result.totalPages(),
                result.hasNext(),
                result.hasPrevious()
        );
    }

    @Transactional(readOnly = true)
    public List<PaymentTransactionView> transactionHistory(
            MerchantApiPrincipal principal,
            String paymentPublicId
    ) {
        PaymentIntent payment = ownershipService.requireOwnedPayment(principal, paymentPublicId);
        return paymentTransactionRepository.findByPaymentIntentId(payment.internalId()).stream()
                .map(PaymentQueryService::toView)
                .toList();
    }

    private static void validateSearch(SearchPaymentIntentsQuery query) {
        if (query.page() < 0) {
            throw invalidQuery("page must not be negative");
        }
        if (query.size() <= 0 || query.size() > MAX_SIZE) {
            throw invalidQuery("size must be between 1 and " + MAX_SIZE);
        }
        if (query.createdFrom() != null
                && query.createdTo() != null
                && query.createdFrom().isAfter(query.createdTo())) {
            throw invalidQuery("createdFrom must not be after createdTo");
        }
    }

    private static PaymentIntentView toView(PaymentIntent payment) {
        return new PaymentIntentView(
                payment.publicId(),
                payment.merchantOrderId(),
                payment.description(),
                payment.amount().amountMinor(),
                payment.amount().currency().getCurrencyCode(),
                payment.status(),
                payment.refundedAmount().amountMinor(),
                payment.refundableAmount().amountMinor(),
                payment.createdAt(),
                payment.updatedAt()
        );
    }

    private static PaymentTransactionView toView(PaymentTransaction transaction) {
        return new PaymentTransactionView(
                transaction.publicId(),
                transaction.attemptNo(),
                transaction.provider(),
                transaction.providerTransactionId(),
                transaction.status(),
                transaction.failureCode(),
                transaction.failureMessage(),
                transaction.startedAt(),
                transaction.completedAt()
        );
    }

    private static ApiException invalidQuery(String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR, detail);
    }
}
