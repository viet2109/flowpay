package com.flowpay.backend.payment.api;

import com.flowpay.backend.common.api.ApiResponse;
import com.flowpay.backend.common.api.PageMeta;
import com.flowpay.backend.common.security.MerchantApiPrincipal;
import com.flowpay.backend.payment.application.PaymentIntentViewPage;
import com.flowpay.backend.payment.application.PaymentQueryService;
import com.flowpay.backend.payment.domain.PaymentStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/v1/payment-intents")
@RequiredArgsConstructor
public class PaymentQueryController {

    private final PaymentQueryService queryService;
    private final PaymentApiMapper mapper;

    @GetMapping
    public ApiResponse<List<PaymentIntentResponse>> list(
            @AuthenticationPrincipal MerchantApiPrincipal principal,
            @RequestParam(required = false) PaymentStatus status,
            @RequestParam(required = false) String orderId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant createdFrom,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant createdTo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        PaymentIntentViewPage result = queryService.search(mapper.toQuery(
                principal,
                status,
                orderId,
                createdFrom,
                createdTo,
                page,
                size
        ));
        return new ApiResponse<>(
                mapper.toPaymentResponses(result.content()),
                new PageMeta(
                        result.page(),
                        result.size(),
                        result.totalElements(),
                        result.totalPages(),
                        result.hasNext(),
                        result.hasPrevious()
                )
        );
    }

    @GetMapping("/{paymentId}")
    public ApiResponse<PaymentIntentResponse> get(
            @AuthenticationPrincipal MerchantApiPrincipal principal,
            @PathVariable String paymentId
    ) {
        return ApiResponse.of(mapper.toResponse(queryService.get(principal, paymentId)));
    }

    @GetMapping("/{paymentId}/transactions")
    public ApiResponse<List<PaymentTransactionResponse>> transactions(
            @AuthenticationPrincipal MerchantApiPrincipal principal,
            @PathVariable String paymentId
    ) {
        return ApiResponse.of(mapper.toTransactionResponses(
                queryService.transactionHistory(principal, paymentId)
        ));
    }
}
