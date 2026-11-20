package com.flowpay.backend.payment.api;

import com.flowpay.backend.common.api.ApiResponse;
import com.flowpay.backend.common.security.DashboardPrincipal;
import com.flowpay.backend.idempotency.application.IdempotencyKeyParser;
import com.flowpay.backend.payment.application.IdempotentConfirmPaymentResult;
import com.flowpay.backend.payment.application.IdempotentConfirmPaymentService;
import com.flowpay.backend.payment.application.IdempotentCreatePaymentResult;
import com.flowpay.backend.payment.application.IdempotentCreatePaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * Create and confirm payments from the dashboard (JWT, OWNER only; the role is
 * enforced in SecurityConfig). Same use cases, idempotency operations and
 * response bodies as {@link PaymentCreationController} and
 * {@link PaymentConfirmationController}, so one Idempotency-Key means one
 * request whichever entry point receives it.
 */
@RestController
@RequestMapping("/api/v1/merchant/payments")
@RequiredArgsConstructor
public class DashboardPaymentCommandController {

    private final IdempotentCreatePaymentService createService;
    private final IdempotentConfirmPaymentService confirmService;
    private final IdempotencyKeyParser idempotencyKeyParser;
    private final PaymentApiMapper mapper;

    @PostMapping
    public ResponseEntity<ApiResponse<CreatePaymentIntentResponse>> create(
            @AuthenticationPrincipal DashboardPrincipal principal,
            @RequestHeader(name = PaymentIdempotencyHeaders.KEY, required = false)
            String rawIdempotencyKey,
            @Valid @RequestBody CreatePaymentIntentRequest request
    ) {
        IdempotentCreatePaymentResult result = createService.create(mapper.toCommand(
                principal.merchantContext(),
                idempotencyKeyParser.parseRequired(rawIdempotencyKey),
                request
        ));
        CreatePaymentIntentResponse response = mapper.toResponse(result.response());
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(result.httpStatus())
                .location(URI.create("/api/v1/merchant/payments/" + response.id()));
        if (result.replayed()) {
            builder.header(PaymentIdempotencyHeaders.REPLAYED, Boolean.TRUE.toString());
        }
        return builder.body(ApiResponse.of(response));
    }

    @PostMapping("/{paymentId}/confirm")
    public ResponseEntity<ApiResponse<ConfirmPaymentResponse>> confirm(
            @AuthenticationPrincipal DashboardPrincipal principal,
            @RequestHeader(name = PaymentIdempotencyHeaders.KEY, required = false)
            String rawIdempotencyKey,
            @PathVariable String paymentId
    ) {
        IdempotentConfirmPaymentResult result = confirmService.confirm(mapper.toConfirmCommand(
                principal.merchantContext(),
                idempotencyKeyParser.parseRequired(rawIdempotencyKey),
                paymentId
        ));
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(result.httpStatus());
        if (result.replayed()) {
            builder.header(PaymentIdempotencyHeaders.REPLAYED, Boolean.TRUE.toString());
        }
        return builder.body(ApiResponse.of(mapper.toResponse(result.response())));
    }
}
