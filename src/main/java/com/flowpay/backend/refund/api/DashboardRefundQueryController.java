package com.flowpay.backend.refund.api;

import com.flowpay.backend.common.api.ApiResponse;
import com.flowpay.backend.common.api.PageMeta;
import com.flowpay.backend.common.security.DashboardPrincipal;
import com.flowpay.backend.refund.application.RefundQueryService;
import com.flowpay.backend.refund.application.RefundViewPage;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Refund reads for the dashboard (JWT, any role). Same use cases and response
 * bodies as {@link RefundQueryController}, which serves API keys.
 */
@RestController
@RequestMapping("/api/v1/merchant")
@RequiredArgsConstructor
public class DashboardRefundQueryController {

    private final RefundQueryService queryService;
    private final RefundApiMapper mapper;

    @GetMapping("/refunds/{refundId}")
    public ApiResponse<RefundResponse> get(
            @AuthenticationPrincipal DashboardPrincipal principal,
            @PathVariable String refundId
    ) {
        return ApiResponse.of(mapper.toResponse(queryService.get(principal.merchantContext(), refundId)));
    }

    @GetMapping("/payments/{paymentId}/refunds")
    public ApiResponse<List<RefundResponse>> list(
            @AuthenticationPrincipal DashboardPrincipal principal,
            @PathVariable String paymentId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        RefundViewPage result = queryService.list(mapper.toQuery(
                principal.merchantContext(),
                paymentId,
                page,
                size
        ));
        return new ApiResponse<>(
                mapper.toResponses(result.content()),
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
}
