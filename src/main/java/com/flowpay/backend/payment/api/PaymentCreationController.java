package com.flowpay.backend.payment.api;

import com.flowpay.backend.common.api.ApiResponse;
import com.flowpay.backend.common.security.MerchantApiPrincipal;
import com.flowpay.backend.idempotency.application.IdempotencyKeyParser;
import com.flowpay.backend.payment.application.IdempotentCreatePaymentResult;
import com.flowpay.backend.payment.application.IdempotentCreatePaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequestMapping("/api/v1/payment-intents")
@RequiredArgsConstructor
public class PaymentCreationController {

    private final IdempotentCreatePaymentService service;
    private final IdempotencyKeyParser idempotencyKeyParser;
    private final PaymentApiMapper mapper;

    @PostMapping
    public ResponseEntity<ApiResponse<CreatePaymentIntentResponse>> create(
            @AuthenticationPrincipal MerchantApiPrincipal principal,
            @RequestHeader(name = PaymentIdempotencyHeaders.KEY, required = false)
            String rawIdempotencyKey,
            @Valid @RequestBody CreatePaymentIntentRequest request
    ) {
        IdempotentCreatePaymentResult result = service.create(mapper.toCommand(
                principal,
                idempotencyKeyParser.parseRequired(rawIdempotencyKey),
                request
        ));
        CreatePaymentIntentResponse response = mapper.toResponse(result.response());
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(result.httpStatus())
                .location(URI.create("/api/v1/payment-intents/" + response.id()));
        if (result.replayed()) {
            builder.header(PaymentIdempotencyHeaders.REPLAYED, Boolean.TRUE.toString());
        }
        return builder.body(ApiResponse.of(response));
    }
}
