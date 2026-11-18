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

    static final long TRIGGER_DECLINED = 901;
    static final long TRIGGER_UNKNOWN = 902;
    static final long TRIGGER_TECHNICAL_FAILURE = 903;

    private final RefundProviderOutcome defaultOutcome;
    private final boolean amountTriggersEnabled;

    SimulatorRefundProvider(RefundProviderOutcome defaultOutcome) {
        this(defaultOutcome, false);
    }

    SimulatorRefundProvider(RefundProviderOutcome defaultOutcome, boolean amountTriggersEnabled) {
        this.defaultOutcome = Objects.requireNonNull(
                defaultOutcome,
                "defaultOutcome must not be null"
        );
        this.amountTriggersEnabled = amountTriggersEnabled;
    }

    @Override
    public RefundProviderResult refund(RefundProviderRequest request) {
        RefundProviderRequest providerRequest = Objects.requireNonNull(
                request,
                "request must not be null"
        );
        return switch (outcomeFor(providerRequest)) {
            case SUCCESS -> success(providerRequest);
            case DECLINED -> declined();
            case UNKNOWN -> unknown();
            case TECHNICAL_FAILURE -> technicalFailure();
        };
    }

    /**
     * Development-only test triggers, as in PSP sandboxes: when enabled, the last
     * three digits of the refund amount in minor units select a non-success
     * outcome, so every outcome is reachable without restarting the process. The
     * result stays a pure function of the request, so replays remain deterministic.
     */
    private RefundProviderOutcome outcomeFor(RefundProviderRequest request) {
        if (!amountTriggersEnabled) {
            return defaultOutcome;
        }
        long trigger = request.amount().amountMinor() % 1000;
        if (trigger == TRIGGER_DECLINED) {
            return RefundProviderOutcome.DECLINED;
        }
        if (trigger == TRIGGER_UNKNOWN) {
            return RefundProviderOutcome.UNKNOWN;
        }
        if (trigger == TRIGGER_TECHNICAL_FAILURE) {
            return RefundProviderOutcome.TECHNICAL_FAILURE;
        }
        return defaultOutcome;
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
