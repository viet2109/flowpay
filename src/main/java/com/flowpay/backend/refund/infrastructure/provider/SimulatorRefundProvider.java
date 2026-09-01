package com.flowpay.backend.refund.infrastructure.provider;

import com.flowpay.backend.refund.application.RefundProviderPort;
import com.flowpay.backend.refund.application.RefundProviderRequest;
import com.flowpay.backend.refund.application.RefundProviderResult;
import com.flowpay.backend.refund.domain.RefundProviderOutcome;

import java.util.Objects;

public final class SimulatorRefundProvider implements RefundProviderPort {

    static final String PROVIDER = "SIMULATOR";
    static final String DECLINED_CODE = "REFUND_DECLINED";
    static final String DECLINED_MESSAGE = "The provider declined the refund.";
    static final String UNKNOWN_CODE = "PROVIDER_TIMEOUT";
    static final String UNKNOWN_MESSAGE = "The refund provider outcome is unknown.";
    static final String TECHNICAL_FAILURE_CODE = "PROVIDER_UNAVAILABLE";
    static final String TECHNICAL_FAILURE_MESSAGE =
            "The refund provider operation did not complete.";

    private final RefundProviderOutcome defaultOutcome;

    SimulatorRefundProvider(RefundProviderOutcome defaultOutcome) {
        this.defaultOutcome = Objects.requireNonNull(
                defaultOutcome,
                "defaultOutcome must not be null"
        );
    }

    @Override
    public RefundProviderResult refund(RefundProviderRequest request) {
        RefundProviderRequest providerRequest = Objects.requireNonNull(
                request,
                "request must not be null"
        );
        return switch (defaultOutcome) {
            case SUCCESS -> success(providerRequest);
            case DECLINED -> declined();
            case UNKNOWN -> unknown();
            case TECHNICAL_FAILURE -> technicalFailure();
        };
    }

    private static RefundProviderResult success(RefundProviderRequest request) {
        return new RefundProviderResult(
                PROVIDER,
                RefundProviderOutcome.SUCCESS,
                refundId(request),
                null,
                null
        );
    }

    private static RefundProviderResult declined() {
        return new RefundProviderResult(
                PROVIDER,
                RefundProviderOutcome.DECLINED,
                null,
                DECLINED_CODE,
                DECLINED_MESSAGE
        );
    }

    private static RefundProviderResult unknown() {
        return new RefundProviderResult(
                PROVIDER,
                RefundProviderOutcome.UNKNOWN,
                null,
                UNKNOWN_CODE,
                UNKNOWN_MESSAGE
        );
    }

    private static RefundProviderResult technicalFailure() {
        return new RefundProviderResult(
                PROVIDER,
                RefundProviderOutcome.TECHNICAL_FAILURE,
                null,
                TECHNICAL_FAILURE_CODE,
                TECHNICAL_FAILURE_MESSAGE
        );
    }

    private static String refundId(RefundProviderRequest request) {
        String publicId = request.refundPublicId();
        return publicId.startsWith("re_")
                ? "sim_refund_" + publicId.substring("re_".length())
                : "sim_refund_" + publicId;
    }
}
