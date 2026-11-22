package com.flowpay.backend.refund.api;

import com.flowpay.backend.common.api.ApiResponse;
import com.flowpay.backend.common.security.DashboardPrincipal;
import com.flowpay.backend.idempotency.application.IdempotencyKeyParser;
import com.flowpay.backend.refund.application.IdempotentRefundResult;
import com.flowpay.backend.refund.application.IdempotentRefundService;
import com.flowpay.backend.refund.domain.RefundReason;
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
 * Refunds from the dashboard (JWT, OWNER only; the role is enforced in
 * SecurityConfig). Same use case, idempotency operation and response body as
 * {@link RefundCreationController}.
 */
@RestController
@RequestMapping("/api/v1/merchant/payments")
@RequiredArgsConstructor
public class DashboardRefundCommandController {

    private final IdempotentRefundService service;
    private final IdempotencyKeyParser idempotencyKeyParser;
    private final RefundApiMapper mapper;

    @PostMapping("/{paymentId}/refunds")
    public ResponseEntity<ApiResponse<RefundResponse>> create(
            @AuthenticationPrincipal DashboardPrincipal principal,
            @RequestHeader(name = RefundIdempotencyHeaders.KEY, required = false)
            String rawIdempotencyKey,
            @PathVariable String paymentId,
            @Valid @RequestBody CreateRefundRequest request
    ) {
        IdempotentRefundResult result = service.create(mapper.toCommand(
                principal.merchantContext(),
                idempotencyKeyParser.parseRequired(rawIdempotencyKey),
                paymentId,
                request,
                RefundReason.of(request.reason())
        ));
        RefundResponse response = mapper.toResponse(result.response());
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(result.httpStatus())
                .location(URI.create("/api/v1/merchant/refunds/" + response.id()));
        if (result.replayed()) {
            builder.header(RefundIdempotencyHeaders.REPLAYED, Boolean.TRUE.toString());
        }
        return builder.body(ApiResponse.of(response));
    }
}
