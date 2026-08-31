package com.flowpay.backend.payment.api;

import com.flowpay.backend.common.api.ApiResponse;
import com.flowpay.backend.common.security.MerchantApiPrincipal;
import com.flowpay.backend.idempotency.application.IdempotencyKeyParser;
import com.flowpay.backend.payment.application.IdempotentConfirmPaymentResult;
import com.flowpay.backend.payment.application.IdempotentConfirmPaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/payment-intents")
@RequiredArgsConstructor
public class PaymentConfirmationController {

    private final IdempotentConfirmPaymentService service;
    private final IdempotencyKeyParser idempotencyKeyParser;
    private final PaymentApiMapper mapper;

    @PostMapping("/{paymentId}/confirm")
    public ResponseEntity<ApiResponse<ConfirmPaymentResponse>> confirm(
            @AuthenticationPrincipal MerchantApiPrincipal principal,
            @RequestHeader(name = PaymentIdempotencyHeaders.KEY, required = false)
            String rawIdempotencyKey,
            @PathVariable String paymentId
    ) {
        IdempotentConfirmPaymentResult result = service.confirm(
                mapper.toConfirmCommand(
                        principal,
                        idempotencyKeyParser.parseRequired(rawIdempotencyKey),
                        paymentId
                )
        );
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(result.httpStatus());
        if (result.replayed()) {
            builder.header(PaymentIdempotencyHeaders.REPLAYED, Boolean.TRUE.toString());
        }
        return builder.body(ApiResponse.of(mapper.toResponse(result.response())));
    }
}
